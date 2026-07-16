# MM-OME-BigTiff-Storage

A high-performance Java library for streaming multi-dimensional microscopy images to
**pyramidal OME-BigTIFF**, with a multiscale (SubIFD) pyramid and concurrent read-while-write.
It is modeled on
[MM-OME-Zarr-Storage](https://github.com/micro-manager/MM-OME-Zarr-Storage) — which itself is
modeled on [NDTiffStorage](https://github.com/micro-manager/NDTiffStorage) — so it shares the
same axes-keyed `putImage` API, a single writer thread with a bounded queue for back-pressure,
and an in-memory write-pending buffer that makes images readable the instant they are queued.
The difference is the output: the community-standard, single-file **OME-TIFF** format (in its
BigTIFF variant, so datasets can exceed 4 GB) that opens directly in QuPath, Fiji/Bio-Formats,
`tifffile`, and libtiff.

The library has **no dependency on Micro-Manager** and no native dependencies — the only runtime
dependency is Jackson (JSON). Its API is deliberately shaped so a Micro-Manager `Storage` adapter
is trivial to write (see [`docs/micromanager-adapter.md`](docs/micromanager-adapter.md) and the
runnable example under `src/test/.../examples/`).

## Features

- **Pyramidal OME-BigTIFF** output: a BigTIFF header (version 43, 8-byte offsets), one top-level
  IFD per full-resolution plane, each carrying a `SubIFDs` tag that points at its reduced-
  resolution levels — exactly the layout produced by `bfconvert`/Bio-Formats and understood by
  QuPath, `tifffile`, ImageJ and libtiff.
- **Automatic multiscale pyramid** by 2×2 block-averaging on `putImage` (configurable number of
  levels), written inline with each plane.
- **Tiled output for very large planes.** Declare a full plane size and write the image as tiles
  (`putTile`), so a plane far larger than a Java array (e.g. a 100,000 × 1,000,000 px stitched
  mosaic) streams in tile-by-tile and readers fetch sub-regions (`getRegion`) by touching only the
  covering tiles at the requested pyramid level. Emits standard tiled OME-TIFF (tags 322–325).
- **Concurrent read-while-write**: reads during acquisition are served from an in-memory
  write-pending buffer, then from disk via positioned channel reads; downsampled levels of a
  still-queued image are synthesized on demand so every level is readable immediately.
- **OME-XML metadata** (`Pixels`, `Channel`, per-plane `TiffData`) in the first IFD's
  `ImageDescription`, plus per-image metadata embedded in a private TIFF tag and kept in an
  append-only NDJSON sidecar for fast indexed reads.
- **Grayscale** GRAY8 / GRAY16 / GRAY32(float) and **8-bit RGB**, uncompressed or **Deflate**
  (zlib) compressed.
- Multi-position acquisitions are written as **one self-contained OME-BigTIFF file per position**.

## Quick start

```java
// Write
OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
    .numResolutionLevels(3)               // build a 3-level pyramid automatically
    .compression(Compression.DEFLATE)
    .pixelSize(0.325).spatialUnit("micrometer")
    .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build())
    .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL)
        .channels(List.of(Channel.builder("DAPI").color("#0000FF").build())).build())
    .addAxis(AxisInfo.builder("z").type(DimensionType.SPACE).build());

OMEBigTiffStorage store = new OMEBigTiffStorage("/data", "acq", summaryJson, cfg);
Map<String,Object> axes = new HashMap<>();
axes.put("time", 0); axes.put("channel", 0); axes.put("z", 0);
store.putImage(pixels /* short[] */, perImageJson, axes,
               false /* rgb */, 16 /* bitDepth */, height, width);
store.finishedWriting();

// Read (works during acquisition too)
OMEBigTiffImage img = store.getImage(axes);      // full resolution
OMEBigTiffImage low = store.getImage(axes, 2);   // downsampled ×4
store.close();

// Re-open an existing dataset
OMEBigTiffStorage ds = OMEBigTiffStorage.load("/data/acq.ome.tiff");
```

### Very large (tiled) images

For planes too large to hold in one array — stitched mosaics covering large areas — declare the
full plane size and a tile size, then write tiles instead of whole planes. Nothing is ever
allocated plane-sized, so memory stays proportional to a tile.

```java
OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
    .fullPlaneSize(100_000, 1_000_000)    // canvas per plane (enables tiled mode)
    .tileSize(512, 512)                    // multiples of 16
    .numResolutionLevels(6)                // pyramid for zoomed-out viewing
    // Tiled mode preallocates one IFD per plane, so Z/C/T counts must be fixed up front:
    .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).count(2).build());

OMEBigTiffStorage store = new OMEBigTiffStorage("/data", "mosaic", summaryJson, cfg);
Map<String,Object> axes = new HashMap<>(); axes.put("channel", 0);
store.putTile(tilePixels /* short[512*512] */, meta, axes,
              tileCol, tileRow, false /* rgb */, 16 /* bitDepth */);
// ... write every tile of every plane ...
store.finishedWriting();

// Read only the pixels you need, at any pyramid level:
OMEBigTiffImage region = store.getRegion(axes, 0 /* level */, x, y, w, h);
```

Whole-plane `getImage` still works for tiled planes that fit in an array (and for all pyramid
levels small enough); on a plane too large it throws, directing you to `getRegion`.

## On-disk layout

```
acq.ome.tiff/                 # the dataset is a folder
  acq.ome.tif                 # single position: one pyramidal OME-BigTIFF file
  ome-metadata.ndjson         # per-image metadata sidecar (append-only)
  mm-bigtiff.json             # MM-specific descriptor (axes, summary, custom metadata)
```

For multi-position data each position is its own self-contained file:

```
acq.ome.tiff/
  acq_p0.ome.tif  acq_p1.ome.tif  acq_p2.ome.tif ...
  ome-metadata.ndjson
  mm-bigtiff.json
```

Each `.ome.tif` file is independently openable by any OME-TIFF reader. Inside a file, the planes
of one position are consecutive top-level IFDs (mapped to Z/C/T by the OME-XML `TiffData`
records), and every full-resolution IFD's `SubIFDs` tag lists that plane's pyramid levels.

## Concurrency model

A single dedicated writer thread drains a bounded queue; `putImage` returns a `Future<Void>` that
completes when the image (and all its pyramid levels) is durably written, and a full queue blocks
the producer (back-pressure). Writer-thread failures are surfaced via
`checkForWritingException()`. Each plane and its whole pyramid are laid out in one in-memory
buffer with all offsets pre-computed and appended in a single write, so the only on-disk patch
between planes is the previous plane's next-IFD pointer. Reads check the write-pending buffer
first (so a just-queued image is immediately visible) and otherwise read the strip directly from
the file with a positioned channel read.

## Building and testing

Builds with a **JDK 8 toolchain** (or newer) and targets **Java 8 bytecode**
(`maven.compiler.source/target=1.8`), so the jar is usable from Java 8 projects such as
Micro-Manager. `mvn package` also produces a self-contained `-all` jar bundling Jackson, for
vendoring into Micro-Manager's `3rdpartypublic/classext`.

```bash
mvn test
mvn package
```

### Cross-language conformance

`VerificationFixtureTest` writes `target/verify-fixture.ome.tiff`; `verify_tiff.py` then reads it
with the independent **`tifffile`** stack and asserts the BigTIFF header, OME-XML dimensions and
channels, the SubIFD pyramid (level sizes), and the exact pixel values (including a 2×2-average
check on level 1):

```bash
mvn test                         # writes the fixture
python verify_tiff.py            # requires: tifffile, numpy
```

## RGB images

8-bit colour is supported alongside grayscale. Pass `rgb = true` (and `bitDepth = 8`) to
`putImage`/`putTile`; the pixel array is Micro-Manager's native **4-bytes-per-pixel** `byte[]`
(interleaved BGRA, alpha unused). It is stored as standard interoperable **3-sample chunky RGB**
(`SamplesPerPixel=3`, `PhotometricInterpretation=RGB`, `BitsPerSample=8,8,8`) so it opens as colour
in QuPath, Fiji/Bio-Formats and `tifffile`. Reads (`getImage`/`getRegion`) return a **3-byte**
interleaved `byte[]` (R,G,B) of `width*height*3` — the exact on-disk layout, with the unused alpha
byte dropped; an adapter that needs MM's 4-byte BGRA back re-pads it. Pyramid downsampling averages
each colour channel independently. 16-bit RGB is not supported (use grayscale for higher bit depths).

```java
store.putImage(bgraPixels /* byte[w*h*4] */, meta, axes,
               true /* rgb */, 8 /* bitDepth */, height, width);
OMEBigTiffImage img = store.getImage(axes);   // img.pix is byte[w*h*3] interleaved R,G,B
```

## Limitations (v1)

- **8-bit RGB only** for colour (GRAY8/GRAY16/GRAY32 for grayscale). 16-bit RGB is rejected with a
  clear error.
- **Fixed pyramid depth.** A plane's SubIFD array is written inline with the plane, so the number
  of resolution levels is fixed once the first image is written; set it up front via
  `numResolutionLevels`. `setMaxResolutionLevel` therefore only raises the level count *before*
  writing begins, unlike the Zarr backend which can back-fill new levels at any time.
- **OME dimension model.** Axes must map to OME-TIFF's `Z`/`C`/`T` (time→T, channel→C, z/space→Z),
  at most one axis per dimension, plus the position axis (separate files). Arbitrary custom axes
  are not representable in OME-TIFF and are rejected with a clear error.
- **Tiled mode is fixed-grid.** Tiled mode needs the full plane size, tile size and Z/C/T counts
  declared up front (the plane IFDs and tile-offset arrays are preallocated); these cannot grow
  after the first `putTile`. The library does not itself place camera fields onto the canvas —
  the caller decides each tile's `(tileCol, tileRow)`. Untiled (single-strip) mode remains the
  default and still discovers dimension sizes dynamically.
