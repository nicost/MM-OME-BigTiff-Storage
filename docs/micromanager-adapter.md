# Wiring MM-OME-BigTiff-Storage into Micro-Manager

MM-OME-BigTiff-Storage has no Micro-Manager dependency. To use it as a Micro-Manager acquisition
backend you write a thin adapter that implements `org.micromanager.data.Storage` and delegates to
`OMEBigTiffStorage`. This adapter lives in the Micro-Manager repository (alongside the existing
`NDTiffAdapter`), not in this library.

A complete, compiling reference implementation — built against small stub interfaces so it needs
no Micro-Manager on the classpath — is in
`src/test/java/org/micromanager/mmomebigtiff/examples/MmStorageAdapterExample.java`, with an
end-to-end test in the same package. The notes below explain the mapping; the example is the
copy-paste starting point.

## The `Storage` SPI

`org.micromanager.data.Storage` (in `mmstudio`) is what the adapter implements. The relevant
methods and how each maps onto this library:

| `Storage` method | MM-OME-BigTiff-Storage call |
| --- | --- |
| `putImage(Image)` | `putImage(pixels, metadataJson, axes, rgb, bitDepth, h, w)` |
| `getImage(Coords)` | `getImage(axes)` → build a `DefaultImage` from `OMEBigTiffImage` |
| `hasImage(Coords)` | `hasImage(axes)` |
| `getMaxIndex(axis)` / `getAxes()` / `getMaxIndices()` | derive from `getAxesSet()` |
| `getSummaryMetadata()` | parse `getSummaryMetadata()` JSON |
| `getNumImages()` | `getAxesSet().size()` |
| `freeze()` | `finishedWriting()` |
| `close()` | `close()` |

## Coords ⇄ axes map

Micro-Manager `Coords` is `axisName -> non-negative Integer`, and **index 0 is omitted** (an
all-zero image has an empty Coords). Convert both ways treating a missing axis as 0:

```java
Map<String,Object> axes = new LinkedHashMap<>();
for (String axis : coords.getAxes()) {
    axes.put(axis, Math.max(coords.getIndex(axis), 0));
}
```

Standard MM axes are `channel`, `z`, `time`, `position` (see `Coords.CHANNEL` etc.). Map
`position` to this library's position axis (default name `"position"`, configurable via
`OMEBigTiffStorageConfig.positionAxis`) so multi-position acquisitions become separate
OME-BigTIFF files.

**Important:** OME-TIFF's dimension model is exactly `Z`/`C`/`T`. Declare each non-position axis
with the matching `DimensionType` (`TIME`, `CHANNEL`, `SPACE`) up front via
`OMEBigTiffStorageConfig.addAxis(...)`, or rely on the built-in name inference for the standard
`time`/`channel`/`z` names. Custom axes that don't map to Z/C/T are rejected — unlike the Zarr
backend, OME-TIFF cannot represent them.

## Metadata as JSON strings

This library takes and returns metadata as opaque JSON **strings**, which is exactly what
Micro-Manager already produces. Convert `SummaryMetadata`/`Metadata` to JSON with
`org.micromanager.internal.propertymap.NonPropertyMapJSONFormats` (via each object's
`toPropertyMap()` / `fromPropertyMap()`), and hand the resulting string to the constructor and to
`putImage`. On read, rebuild `Metadata` from the returned `OMEBigTiffImage.metadataJson`.

`OMEBigTiffStorage.setCustomMetadata(key, json)` / `getCustomMetadata(key)` give you a place for
anything outside the OME schema (it is stored in the `mm-bigtiff.json` descriptor and never
pollutes the OME-XML).

## Lazy creation after SummaryMetadata

As with `NDTiffAdapter`, the store cannot be constructed until summary metadata is available (it
carries pixel dimensions, axis order, channel names, etc.). Subscribe to the Datastore's
`DataProviderHasNewSummaryMetadataEvent` and construct `OMEBigTiffStorage` in that handler, before
the first `putImage`.

## Pixel types and resolution

Build the `(rgb, bitDepth)` pair from `Image.getNumComponents()` and `Image.getBytesPerPixel()`.
This library supports GRAY8/GRAY16/GRAY32 and 8-bit RGB. For RGB, Micro-Manager reports
`getNumComponents() == 3` with `getBytesPerPixel() == 4`, so `rgb = true` and `bitDepth = 8`; hand
the raw 4-byte-per-pixel `byte[]` (BGRA, alpha unused) straight to `putImage`/`putTile`. **Read
contract:** `getImage`/`getRegion` return a **3-byte** interleaved RGB `byte[]` (`w*h*3`, order
R,G,B), matching what is on disk. If the adapter needs Micro-Manager's 4-byte BGRA layout back
(e.g. to build a `DefaultImage`), re-pad the 3 bytes to 4 and reorder R,G,B → B,G,R,A.
Micro-Manager's Datastore is single-resolution, so the
adapter only ever uses resolution level 0 — the pyramid is written to disk (as SubIFDs) for
downstream viewers, but the adapter neither reads nor is asked for levels > 0.

Set `numResolutionLevels` on the config **before** the first image; the pyramid depth is fixed for
the life of the file (see the README limitations).

## Large mosaics (tiled mode)

For acquisitions that stitch many fields into one very large plane (bigger than a Java array can
hold), configure tiled mode instead of one `putImage` per plane: set
`OMEBigTiffStorageConfig.fullPlaneSize(w, h)` and `tileSize(tw, th)`, declare fixed Z/C/T counts,
and feed each camera field to `putTile(pixels, meta, axes, tileCol, tileRow, rgb, bitDepth)` at its
grid position (the adapter computes `tileCol/tileRow` from the field's stage/tile index). Readers
pull sub-regions with `getRegion(axes, level, x, y, w, h)`. This is the path to use when a
Micro-Manager acquisition would otherwise exceed the single-strip / whole-array limits; ordinary
single-frame acquisitions keep using `putImage`.

## Registering the backend

To let Micro-Manager choose this backend, add a dataset-type sniff (analogous to
`NDTiffAdapter.isNDTiffDataSet(dir)` — e.g. presence of an `mm-bigtiff.json` descriptor, or an
`*.ome.tif` whose first IFD carries OME-XML) and wire it into `DefaultDataManager` and
`MMAcquisition.getAppropriateStorage()`.
