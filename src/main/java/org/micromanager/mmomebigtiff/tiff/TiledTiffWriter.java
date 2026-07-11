package org.micromanager.mmomebigtiff.tiff;

import org.micromanager.mmomebigtiff.Compression;
import org.micromanager.mmomebigtiff.Downsampler;
import org.micromanager.mmomebigtiff.PixelType;
import org.micromanager.mmomebigtiff.Version;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Streaming writer for one <b>tiled</b> pyramidal OME-BigTIFF file (one stage position), for
 * planes far larger than a single strip or a Java array can hold. Every plane spans a fixed
 * canvas and is stored as {@code tileWidth}×{@code tileHeight} tiles (TIFF tags 322–325), so a
 * plane streams in tile-by-tile and readers can fetch a sub-region by touching only the covering
 * tiles at the requested pyramid level.
 *
 * <p>Layout: a BigTIFF header, then all plane IFDs (level 0 chained via the next-IFD offset, each
 * carrying a {@code SubIFDs} tag to its reduced-resolution levels), each IFD's out-of-line
 * {@code TileOffsets}/{@code TileByteCounts} arrays reserved (zero-filled) up front. Tile pixel
 * data is then appended to the file as {@link #writeTile} is called, patching the two array slots
 * for that tile. Because every offset is explicit, tiles for different planes may be written in
 * any order. Reduced pyramid levels are synthesized from the level-0 tiles by {@link #finish}.
 *
 * <p>All in-memory buffers are tile-sized, never plane-sized, so memory use is independent of the
 * canvas size.
 */
public final class TiledTiffWriter implements AutoCloseable {

   // --- BigTIFF field types ---
   private static final int TYPE_ASCII = 2;
   private static final int TYPE_SHORT = 3;
   private static final int TYPE_LONG = 4;
   private static final int TYPE_RATIONAL = 5;
   private static final int TYPE_LONG8 = 16;
   private static final int TYPE_IFD8 = 18;

   // --- Tags ---
   private static final int TAG_NEW_SUBFILE_TYPE = 254;
   private static final int TAG_IMAGE_WIDTH = 256;
   private static final int TAG_IMAGE_LENGTH = 257;
   private static final int TAG_BITS_PER_SAMPLE = 258;
   private static final int TAG_COMPRESSION = 259;
   private static final int TAG_PHOTOMETRIC = 262;
   private static final int TAG_IMAGE_DESCRIPTION = 270;
   private static final int TAG_SAMPLES_PER_PIXEL = 277;
   private static final int TAG_X_RESOLUTION = 282;
   private static final int TAG_Y_RESOLUTION = 283;
   private static final int TAG_RESOLUTION_UNIT = 296;
   private static final int TAG_SOFTWARE = 305;
   private static final int TAG_TILE_WIDTH = 322;
   private static final int TAG_TILE_LENGTH = 323;
   private static final int TAG_TILE_OFFSETS = 324;
   private static final int TAG_TILE_BYTE_COUNTS = 325;
   private static final int TAG_SUB_IFDS = 330;
   private static final int TAG_SAMPLE_FORMAT = 339;

   private static final int IFD_ENTRY_BYTES = 20;
   private static final int HEADER_BYTES = 16;

   private final Path file;
   private final ByteOrder order;
   private final PixelType type;
   private final int numLevels;
   private final int numPlanes;
   private final int tileW;
   private final int tileH;
   private final long canvasW;
   private final long canvasH;
   private final Compression compression;
   private final byte[] softwareBytes;
   private final double pixelsPerCm;

   private final RandomAccessFile raf;
   private final FileChannel channel;

   // Per-level geometry.
   private final long[] levelW;
   private final long[] levelH;
   private final int[] tilesAcross;
   private final int[] tilesDown;

   // File offset of each (plane,level)'s TileOffsets / TileByteCounts arrays.
   private final long[][] tileOffsetsArrayPos;
   private final long[][] tileByteCountsArrayPos;
   // File location of plane 0 / level 0's ImageDescription entry (patched with OME-XML at finish).
   private long imageDescEntryLoc = -1;

   private long writePos;      // next free file offset (start of / within the tile-data region)
   private boolean finished;

   public TiledTiffWriter(Path file, PixelType type, long canvasWidth, long canvasHeight,
                          int tileWidth, int tileHeight, int numLevels, int numPlanes,
                          Compression compression, ByteOrder order, double pixelSizeUm)
         throws IOException {
      if (canvasWidth <= 0 || canvasHeight <= 0) {
         throw new IllegalArgumentException("canvas dimensions must be positive");
      }
      if (tileWidth <= 0 || tileHeight <= 0 || (tileWidth % 16) != 0 || (tileHeight % 16) != 0) {
         throw new IllegalArgumentException("tile dimensions must be positive multiples of 16");
      }
      if (numPlanes <= 0) {
         throw new IllegalArgumentException("numPlanes must be positive");
      }
      this.file = file;
      this.type = type;
      this.canvasW = canvasWidth;
      this.canvasH = canvasHeight;
      this.tileW = tileWidth;
      this.tileH = tileHeight;
      this.numLevels = Math.max(1, numLevels);
      this.numPlanes = numPlanes;
      this.compression = compression == null ? Compression.NONE : compression;
      this.order = order == null ? ByteOrder.LITTLE_ENDIAN : order;
      this.softwareBytes = asciiz(Version.SOFTWARE);
      this.pixelsPerCm = pixelSizeUm > 0 ? 1.0e4 / pixelSizeUm : 1.0e4;

      this.levelW = new long[this.numLevels];
      this.levelH = new long[this.numLevels];
      this.tilesAcross = new int[this.numLevels];
      this.tilesDown = new int[this.numLevels];
      long lw = canvasWidth;
      long lh = canvasHeight;
      for (int l = 0; l < this.numLevels; l++) {
         levelW[l] = lw;
         levelH[l] = lh;
         tilesAcross[l] = (int) ((lw + tileW - 1) / tileW);
         tilesDown[l] = (int) ((lh + tileH - 1) / tileH);
         lw = (lw + 1) / 2;
         lh = (lh + 1) / 2;
      }

      this.tileOffsetsArrayPos = new long[numPlanes][this.numLevels];
      this.tileByteCountsArrayPos = new long[numPlanes][this.numLevels];

      this.raf = new RandomAccessFile(file.toFile(), "rw");
      this.raf.setLength(0);
      this.channel = raf.getChannel();
      writeAllIfds();
   }

   public Path getFile() {
      return file;
   }

   public int getNumLevels() {
      return numLevels;
   }

   public int tilesAcross(int level) {
      return tilesAcross[level];
   }

   public int tilesDown(int level) {
      return tilesDown[level];
   }

   public int tileCount(int level) {
      return tilesAcross[level] * tilesDown[level];
   }

   public long levelWidth(int level) {
      return levelW[level];
   }

   public long levelHeight(int level) {
      return levelH[level];
   }

   public int tileWidth() {
      return tileW;
   }

   public int tileHeight() {
      return tileH;
   }

   // -------------------------------------------------------------------------
   // Write
   // -------------------------------------------------------------------------

   /**
    * Write one tile of a plane. The pixel array must hold exactly {@code tileWidth*tileHeight}
    * samples (edge tiles are zero-padded by the caller).
    *
    * @param planeIndex level-0 IFD index of the plane (0-based, in file order)
    * @param level      pyramid level (0 = full resolution)
    * @param tileCol    tile column at that level
    * @param tileRow    tile row at that level
    * @param tilePixels primitive pixel array of length {@code tileWidth*tileHeight}
    */
   public synchronized void writeTile(int planeIndex, int level, int tileCol, int tileRow,
                                      Object tilePixels) throws IOException {
      if (finished) {
         throw new IllegalStateException("Writer already finished: " + file);
      }
      checkPlaneLevel(planeIndex, level);
      if (tileCol < 0 || tileCol >= tilesAcross[level] || tileRow < 0
            || tileRow >= tilesDown[level]) {
         throw new IndexOutOfBoundsException("tile (" + tileCol + "," + tileRow + ") out of grid "
               + tilesAcross[level] + "x" + tilesDown[level] + " at level " + level);
      }
      writeTileInternal(planeIndex, level, tileCol, tileRow, tilePixels);
   }

   /** Whether a tile has been written (its on-disk offset slot is non-zero). */
   public synchronized boolean hasTile(int planeIndex, int level, int tileCol, int tileRow)
         throws IOException {
      checkPlaneLevel(planeIndex, level);
      int tileIndex = tileRow * tilesAcross[level] + tileCol;
      return readArraySlot(tileByteCountsArrayPos[planeIndex][level], tileIndex) != 0
            || readArraySlot(tileOffsetsArrayPos[planeIndex][level], tileIndex) != 0;
   }

   /** Read one tile back into a primitive pixel array of length {@code tileWidth*tileHeight}. */
   public synchronized Object readTile(int planeIndex, int level, int tileCol, int tileRow)
         throws IOException {
      checkPlaneLevel(planeIndex, level);
      int tileIndex = tileRow * tilesAcross[level] + tileCol;
      long off = readArraySlot(tileOffsetsArrayPos[planeIndex][level], tileIndex);
      long count = readArraySlot(tileByteCountsArrayPos[planeIndex][level], tileIndex);
      int numPixels = tileW * tileH;
      if (off == 0 || count == 0) {
         return TiffPixelCodec.fromRawBytes(new byte[TiffPixelCodec.rawByteCount(type, numPixels)],
               type, order, numPixels);
      }
      ByteBuffer bb = ByteBuffer.allocate((int) count);
      while (bb.hasRemaining()) {
         int n = channel.read(bb, off + bb.position());
         if (n < 0) {
            break;
         }
      }
      byte[] onDisk = bb.array();
      byte[] raw = compression == Compression.DEFLATE
            ? TiffPixelCodec.inflate(onDisk, TiffPixelCodec.rawByteCount(type, numPixels))
            : onDisk;
      return TiffPixelCodec.fromRawBytes(raw, type, order, numPixels);
   }

   /** Read an arbitrary region of a plane/level, gathering only the covering tiles. */
   public synchronized Object readRegion(int planeIndex, int level, long x, long y, int w, int h)
         throws IOException {
      checkPlaneLevel(planeIndex, level);
      return Tiles.readRegion((lvl, tc, tr) -> readTile(planeIndex, lvl, tc, tr),
            type, level, tileW, tileH, levelW[level], levelH[level], x, y, w, h);
   }

   // -------------------------------------------------------------------------
   // Finish (pyramid synthesis + OME-XML)
   // -------------------------------------------------------------------------

   /** Synthesize pyramid levels from level-0 tiles, embed the OME-XML, and finalize the file. */
   public synchronized void finish(byte[] omeXmlUtf8) throws IOException {
      if (finished) {
         return;
      }
      generatePyramid();
      if (imageDescEntryLoc >= 0 && omeXmlUtf8 != null) {
         byte[] descBytes = new byte[omeXmlUtf8.length + 1]; // + NUL terminator
         System.arraycopy(omeXmlUtf8, 0, descBytes, 0, omeXmlUtf8.length);
         long descOff = pad2(writePos);
         ByteBuffer bb = ByteBuffer.allocate((int) (descOff - writePos) + descBytes.length);
         for (long i = writePos; i < descOff; i++) {
            bb.put((byte) 0);
         }
         bb.put(descBytes);
         bb.flip();
         writeAt(writePos, bb);
         writePos = descOff + descBytes.length;
         patchLong(imageDescEntryLoc + 4, descBytes.length); // count (LONG8)
         patchLong(imageDescEntryLoc + 12, descOff);         // value offset (LONG8)
      }
      channel.force(true);
      raf.setLength(writePos);
      finished = true;
   }

   private void generatePyramid() throws IOException {
      for (int l = 1; l < numLevels; l++) {
         for (int p = 0; p < numPlanes; p++) {
            for (int tr = 0; tr < tilesDown[l]; tr++) {
               for (int tc = 0; tc < tilesAcross[l]; tc++) {
                  Object tile = downsampledTile(p, l, tc, tr);
                  if (tile != null) {
                     writeTileInternal(p, l, tc, tr, tile);
                  }
               }
            }
         }
      }
   }

   /**
    * Build one level-{@code l} tile from the four covering level-{@code l-1} tiles, or null if
    * none of the source tiles were written (leave the output tile absent).
    */
   private Object downsampledTile(int plane, int l, int tc, int tr) throws IOException {
      int src = l - 1;
      // The 2x2 block of source tiles covering this output tile.
      long blockW = Math.min(2L * tileW, levelW[src] - (long) 2 * tc * tileW);
      long blockH = Math.min(2L * tileH, levelH[src] - (long) 2 * tr * tileH);
      if (blockW <= 0 || blockH <= 0) {
         return null;
      }
      int bw = (int) blockW;
      int bh = (int) blockH;
      Object block = TiffPixelCodec.blankTile(type, bw * bh);
      boolean any = false;
      for (int dr = 0; dr < 2; dr++) {
         for (int dc = 0; dc < 2; dc++) {
            int stc = 2 * tc + dc;
            int str = 2 * tr + dr;
            if (stc >= tilesAcross[src] || str >= tilesDown[src]) {
               continue;
            }
            if (!hasTile(plane, src, stc, str)) {
               continue;
            }
            any = true;
            Object srcTile = readTile(plane, src, stc, str);
            // Copy the valid part of this source tile into the block at (dc*tileW, dr*tileH).
            int dstX = dc * tileW;
            int dstY = dr * tileH;
            int copyW = Math.min(tileW, bw - dstX);
            int copyH = Math.min(tileH, bh - dstY);
            TiffPixelCodec.copyRegion(srcTile, tileW, 0, 0, block, bw, dstX, dstY, copyW, copyH);
         }
      }
      if (!any) {
         return null;
      }
      Object reduced = Downsampler.downsample(block, bw, bh, type); // ceil(bw/2) x ceil(bh/2)
      int rw = Downsampler.downWidth(bw);
      int rh = Downsampler.downHeight(bh);
      Object out = TiffPixelCodec.blankTile(type, tileW * tileH);
      TiffPixelCodec.copyRegion(reduced, rw, 0, 0, out, tileW, 0, 0,
            Math.min(rw, tileW), Math.min(rh, tileH));
      return out;
   }

   // Internal write that bypasses the finished check (used during finish()).
   private void writeTileInternal(int planeIndex, int level, int tileCol, int tileRow,
                                  Object tilePixels) throws IOException {
      byte[] raw = TiffPixelCodec.toRawBytes(tilePixels, type, order);
      byte[] bytes = compression == Compression.DEFLATE ? TiffPixelCodec.deflate(raw) : raw;
      int tileIndex = tileRow * tilesAcross[level] + tileCol;
      long off = writePos; // writePos is kept even; tile offsets stay word-aligned
      writeAt(off, ByteBuffer.wrap(bytes));
      writePos = pad2(off + bytes.length);
      patchLong(tileOffsetsArrayPos[planeIndex][level] + (long) tileIndex * 8, off);
      patchLong(tileByteCountsArrayPos[planeIndex][level] + (long) tileIndex * 8, bytes.length);
   }

   @Override
   public synchronized void close() {
      try {
         channel.close();
      } catch (IOException ignore) {
         // best effort
      }
      try {
         raf.close();
      } catch (IOException ignore) {
         // best effort
      }
   }

   // -------------------------------------------------------------------------
   // IFD region layout + writing
   // -------------------------------------------------------------------------

   private void writeAllIfds() throws IOException {
      // Header.
      ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES).order(order);
      h.putShort(0, order == ByteOrder.BIG_ENDIAN ? (short) 0x4D4D : (short) 0x4949);
      h.putShort(2, (short) 43);
      h.putShort(4, (short) 8);
      h.putShort(6, (short) 0);

      // Pass 1: assign a file offset to every IFD and every out-of-line array.
      long cursor = HEADER_BYTES;
      long[][] ifdPos = new long[numPlanes][numLevels];
      long[] subIfdsArrayPos = new long[numPlanes];
      long softwarePos;
      // Compute IFD sizes first so we can interleave arrays after each IFD.
      for (int p = 0; p < numPlanes; p++) {
         for (int l = 0; l < numLevels; l++) {
            ifdPos[p][l] = cursor;
            int entries = entryCount(p, l);
            cursor += ifdBytes(entries);
            // out-of-line tile arrays for this IFD
            tileOffsetsArrayPos[p][l] = cursor;
            cursor += arrayBytes(tileCount(l));
            tileByteCountsArrayPos[p][l] = cursor;
            cursor += arrayBytes(tileCount(l));
         }
         if (numLevels > 2) {
            subIfdsArrayPos[p] = cursor;
            cursor += 8L * (numLevels - 1);
         } else {
            subIfdsArrayPos[p] = -1;
         }
      }
      softwarePos = cursor;
      cursor += pad2(softwareBytes.length);
      this.writePos = cursor; // tile data starts after the IFD region

      h.putLong(8, ifdPos[0][0]); // first IFD
      writeAt(0, h);

      // Software string (shared by all IFDs).
      writeAt(softwarePos, ByteBuffer.wrap(softwareBytes));

      // Pass 2: write each IFD.
      for (int p = 0; p < numPlanes; p++) {
         for (int l = 0; l < numLevels; l++) {
            long nextIfd = 0;
            if (l == 0 && p + 1 < numPlanes) {
               nextIfd = ifdPos[p + 1][0]; // chain level-0 IFDs in plane order
            }
            writeIfd(p, l, ifdPos[p][l], softwarePos, subIfdsArrayPos[p], ifdPos[p], nextIfd);
         }
      }
   }

   private int entryCount(int plane, int level) {
      // 254,256,257,258,259,262,277,282,283,296,305,322,323,324,325,339 = 16 base entries.
      int n = 16;
      if (level == 0 && numLevels > 1) {
         n += 1; // SubIFDs
      }
      if (plane == 0 && level == 0) {
         n += 1; // ImageDescription (OME-XML)
      }
      return n;
   }

   private void writeIfd(int plane, int level, long at, long softwarePos, long subIfdsArrayPos,
                         long[] planeIfdPos, long nextIfd) throws IOException {
      int n = entryCount(plane, level);
      ByteBuffer buf = ByteBuffer.allocate((int) ifdBytes(n)).order(order);
      buf.putLong(0, n);
      int e = 8;
      long tiles = tileCount(level);

      e = entryLong(buf, e, TAG_NEW_SUBFILE_TYPE, level == 0 ? 0 : 1);
      e = entryLong(buf, e, TAG_IMAGE_WIDTH, levelW[level]);
      e = entryLong(buf, e, TAG_IMAGE_LENGTH, levelH[level]);
      e = entryShort(buf, e, TAG_BITS_PER_SAMPLE, type.bitDepth());
      e = entryShort(buf, e, TAG_COMPRESSION, compression.tiffCode());
      e = entryShort(buf, e, TAG_PHOTOMETRIC, 1);
      if (plane == 0 && level == 0) {
         imageDescEntryLoc = at + e; // absolute file location; patched at finish()
         e = entryOffset(buf, e, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, 1, 0);
      }
      e = entryShort(buf, e, TAG_SAMPLES_PER_PIXEL, 1);
      e = entryRational(buf, e, TAG_X_RESOLUTION, resNumerator(level), 1);
      e = entryRational(buf, e, TAG_Y_RESOLUTION, resNumerator(level), 1);
      e = entryShort(buf, e, TAG_RESOLUTION_UNIT, 3);
      e = entryOffset(buf, e, TAG_SOFTWARE, TYPE_ASCII, softwareBytes.length, softwarePos);
      e = entryLong(buf, e, TAG_TILE_WIDTH, tileW);
      e = entryLong(buf, e, TAG_TILE_LENGTH, tileH);
      // A single-tile level stores its one offset/bytecount inline in the entry's 8-byte value
      // field (TIFF requires values that fit to be inline). Point the patch location there so
      // writeTile/readTile work uniformly; otherwise use the reserved out-of-line array.
      if (tiles == 1) {
         tileOffsetsArrayPos[plane][level] = at + e + 12;
         e = entryLong8(buf, e, TAG_TILE_OFFSETS, TYPE_LONG8, 0L);
         tileByteCountsArrayPos[plane][level] = at + e + 12;
         e = entryLong8(buf, e, TAG_TILE_BYTE_COUNTS, TYPE_LONG8, 0L);
      } else {
         e = entryOffset(buf, e, TAG_TILE_OFFSETS, TYPE_LONG8, tiles,
               tileOffsetsArrayPos[plane][level]);
         e = entryOffset(buf, e, TAG_TILE_BYTE_COUNTS, TYPE_LONG8, tiles,
               tileByteCountsArrayPos[plane][level]);
      }
      if (level == 0 && numLevels > 1) {
         int subCount = numLevels - 1;
         if (subCount == 1) {
            e = entryLong8(buf, e, TAG_SUB_IFDS, TYPE_IFD8, planeIfdPos[1]);
         } else {
            // Fill the SubIFDs array with the level 1..N-1 IFD offsets for this plane.
            ByteBuffer arr = ByteBuffer.allocate(8 * subCount).order(order);
            for (int l = 1; l < numLevels; l++) {
               arr.putLong((l - 1) * 8, planeIfdPos[l]);
            }
            arr.flip();
            writeAt(subIfdsArrayPos, arr);
            e = entryOffset(buf, e, TAG_SUB_IFDS, TYPE_IFD8, subCount, subIfdsArrayPos);
         }
      }
      e = entryShort(buf, e, TAG_SAMPLE_FORMAT, type.sampleFormat());
      buf.putLong(e, nextIfd);
      writeAt(at, buf);
   }

   private long resNumerator(int level) {
      long n = Math.round(pixelsPerCm / Math.pow(2, level));
      return n <= 0 ? 1 : n;
   }

   // For a 1-tile level, TileOffsets/TileByteCounts are stored inline in the IFD entry's value
   // field; expose those field locations so writeTile can patch them.
   private long readArraySlot(long arrayPos, int tileIndex) throws IOException {
      ByteBuffer b = ByteBuffer.allocate(8).order(order);
      long pos = arrayPos + (long) tileIndex * 8;
      while (b.hasRemaining()) {
         int nread = channel.read(b, pos + b.position());
         if (nread < 0) {
            break;
         }
      }
      return b.getLong(0);
   }

   private void checkPlaneLevel(int plane, int level) {
      if (plane < 0 || plane >= numPlanes) {
         throw new IndexOutOfBoundsException("plane " + plane + " out of range [0," + numPlanes + ")");
      }
      if (level < 0 || level >= numLevels) {
         throw new IndexOutOfBoundsException("level " + level + " out of range [0," + numLevels + ")");
      }
   }

   // -------------------------------------------------------------------------
   // IFD entry primitives (each writes 20 bytes, returns next entry offset)
   // -------------------------------------------------------------------------

   private int entryHeader(ByteBuffer buf, int at, int tag, int type, long count) {
      buf.putShort(at, (short) tag);
      buf.putShort(at + 2, (short) type);
      buf.putLong(at + 4, count);
      buf.putLong(at + 12, 0L);
      return at + 12;
   }

   private int entryShort(ByteBuffer buf, int at, int tag, int value) {
      int v = entryHeader(buf, at, tag, TYPE_SHORT, 1);
      buf.putShort(v, (short) value);
      return at + IFD_ENTRY_BYTES;
   }

   private int entryLong(ByteBuffer buf, int at, int tag, long value) {
      int v = entryHeader(buf, at, tag, TYPE_LONG, 1);
      buf.putInt(v, (int) value);
      return at + IFD_ENTRY_BYTES;
   }

   private int entryLong8(ByteBuffer buf, int at, int tag, int type, long value) {
      int v = entryHeader(buf, at, tag, type, 1);
      buf.putLong(v, value);
      return at + IFD_ENTRY_BYTES;
   }

   private int entryRational(ByteBuffer buf, int at, int tag, long num, long den) {
      int v = entryHeader(buf, at, tag, TYPE_RATIONAL, 1);
      buf.putInt(v, (int) num);
      buf.putInt(v + 4, (int) den);
      return at + IFD_ENTRY_BYTES;
   }

   private int entryOffset(ByteBuffer buf, int at, int tag, int type, long count, long offset) {
      int v = entryHeader(buf, at, tag, type, count);
      buf.putLong(v, offset);
      return at + IFD_ENTRY_BYTES;
   }

   // -------------------------------------------------------------------------
   // Low-level helpers
   // -------------------------------------------------------------------------

   private void writeAt(long pos, ByteBuffer buf) throws IOException {
      buf.rewind();
      while (buf.hasRemaining()) {
         pos += channel.write(buf, pos);
      }
   }

   private void patchLong(long loc, long value) throws IOException {
      ByteBuffer b = ByteBuffer.allocate(8).order(order);
      b.putLong(0, value);
      writeAt(loc, b);
   }

   private static long ifdBytes(int numEntries) {
      return 8L + (long) numEntries * IFD_ENTRY_BYTES + 8L;
   }

   private static long arrayBytes(long tiles) {
      return tiles == 1 ? 0L : tiles * 8L; // single-tile arrays live inline in the IFD entry
   }

   private static long pad2(long v) {
      return (v & 1L) == 0 ? v : v + 1;
   }

   private static byte[] asciiz(String s) {
      byte[] raw = s.getBytes(StandardCharsets.UTF_8);
      byte[] out = new byte[raw.length + 1];
      System.arraycopy(raw, 0, out, 0, raw.length);
      return out;
   }
}
