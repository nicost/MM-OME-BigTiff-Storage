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
 * Streaming writer for one pyramidal OME-BigTIFF file (one stage position). Produces the layout
 * emitted by {@code bfconvert}/Bio-Formats and read by QuPath, tifffile and libtiff:
 *
 * <ul>
 *   <li>a BigTIFF header (version 43, 8-byte offsets);</li>
 *   <li>one top-level IFD per full-resolution plane, chained via the next-IFD offset;</li>
 *   <li>each full-resolution IFD carries a {@code SubIFDs} tag pointing at its reduced-resolution
 *       pyramid levels (each a self-contained IFD marked {@code NewSubfileType=1});</li>
 *   <li>the OME-XML in the first IFD's {@code ImageDescription}, written at {@link #finish} once
 *       all planes (and therefore the full dimension sizes) are known.</li>
 * </ul>
 *
 * <p>Each plane and all of its pyramid levels are laid out in a single in-memory buffer whose
 * offsets are computed up front and appended to the file in one write, so the only cross-plane
 * link that must be patched on disk is the previous plane's next-IFD pointer. Positioned
 * {@link FileChannel} reads make {@link #readLevel} safe to call from other threads while writing
 * proceeds.
 */
public final class TiffPyramidWriter implements AutoCloseable {

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
   private static final int TAG_STRIP_OFFSETS = 273;
   private static final int TAG_SAMPLES_PER_PIXEL = 277;
   private static final int TAG_ROWS_PER_STRIP = 278;
   private static final int TAG_STRIP_BYTE_COUNTS = 279;
   private static final int TAG_X_RESOLUTION = 282;
   private static final int TAG_Y_RESOLUTION = 283;
   private static final int TAG_RESOLUTION_UNIT = 296;
   private static final int TAG_SOFTWARE = 305;
   private static final int TAG_SUB_IFDS = 330;
   private static final int TAG_SAMPLE_FORMAT = 339;
   // Private per-image metadata tag (a JSON string). Deliberately NOT NDTiffStorage's 51123,
   // whose value makes readers (e.g. tifffile) probe for the unrelated NDTiff file header.
   private static final int TAG_MM_METADATA = 65000;

   private static final int IFD_ENTRY_BYTES = 20;
   private static final int HEADER_BYTES = 16;

   private final Path file;
   private final ByteOrder order;
   private final PixelType type;
   private final int numLevels;
   private final Compression compression;
   private final byte[] softwareBytes;
   private final double pixelsPerCm; // level-0 resolution for the X/Y resolution tags

   private final RandomAccessFile raf;
   private final FileChannel channel;

   private long writePos;                 // next free file offset
   private boolean headerWritten;
   private long prevNextIfdFieldLoc = -1; // on-disk location of the last plane's next-IFD pointer
   private long imageDescEntryLoc = -1;   // on-disk location of the first IFD's ImageDescription entry
   private boolean finished;

   public TiffPyramidWriter(Path file, PixelType type, int numLevels, Compression compression,
                            ByteOrder order, double pixelSizeUm) throws IOException {
      this.file = file;
      this.type = type;
      this.numLevels = Math.max(1, numLevels);
      this.compression = compression == null ? Compression.NONE : compression;
      this.order = order == null ? ByteOrder.LITTLE_ENDIAN : order;
      this.softwareBytes = asciiz(Version.SOFTWARE);
      this.pixelsPerCm = pixelSizeUm > 0 ? 1.0e4 / pixelSizeUm : 1.0e4;
      this.raf = new RandomAccessFile(file.toFile(), "rw");
      this.raf.setLength(0);
      this.channel = raf.getChannel();
   }

   public Path getFile() {
      return file;
   }

   public int getNumLevels() {
      return numLevels;
   }

   // -------------------------------------------------------------------------
   // Write
   // -------------------------------------------------------------------------

   /**
    * Append one full plane and all of its pyramid levels.
    *
    * @param level0Pixels full-resolution primitive pixel array
    * @param width0       full-resolution width
    * @param height0      full-resolution height
    * @param metadataJson per-image metadata JSON (may be null); embedded in a private tag
    * @return the on-disk location of every level's strip
    */
   public synchronized PlaneLocation writePlane(Object level0Pixels, int width0, int height0,
                                                String metadataJson) throws IOException {
      if (finished) {
         throw new IllegalStateException("Writer already finished: " + file);
      }
      if (!headerWritten) {
         writeHeader();
      }

      // Compute pixels for every level (level 0 is the caller's array).
      Object[] levelPix = new Object[numLevels];
      int[] w = new int[numLevels];
      int[] h = new int[numLevels];
      levelPix[0] = level0Pixels;
      w[0] = width0;
      h[0] = height0;
      for (int l = 1; l < numLevels; l++) {
         levelPix[l] = Downsampler.downsample(levelPix[l - 1], w[l - 1], h[l - 1], type);
         w[l] = Downsampler.downWidth(w[l - 1]);
         h[l] = Downsampler.downHeight(h[l - 1]);
      }

      // Encode (and optionally compress) each level's strip.
      byte[][] strip = new byte[numLevels][];
      for (int l = 0; l < numLevels; l++) {
         byte[] raw = TiffPixelCodec.toRawBytes(levelPix[l], type, order);
         strip[l] = compression == Compression.DEFLATE ? TiffPixelCodec.deflate(raw) : raw;
      }

      final boolean firstPlane = !headerPatchedFirstIfd();
      final byte[] metaBytes = metadataJson == null ? null : asciiz(metadataJson);

      // ---- Pass 1: lay out the unit, computing every offset relative to base = writePos ----
      final long base = writePos;
      int n0 = level0EntryCount(firstPlane, metaBytes != null);
      long i0size = ifdBytes(n0);

      long pos = base;
      long level0IfdOff = pos;
      pos += i0size;
      long softwareOff = pos;
      pos += pad2(softwareBytes.length);
      long metaOff = -1;
      if (metaBytes != null) {
         metaOff = pos;
         pos += pad2(metaBytes.length);
      }
      long subArrayOff = -1;
      if (numLevels - 1 >= 2) {
         subArrayOff = pos;
         pos += 8L * (numLevels - 1);
      }
      long[] stripOff = new long[numLevels];
      long[] subIfdOff = new long[Math.max(0, numLevels - 1)];
      stripOff[0] = pos;
      pos += pad2(strip[0].length);
      long subIfdSize = ifdBytes(SUB_IFD_ENTRIES);
      for (int l = 1; l < numLevels; l++) {
         subIfdOff[l - 1] = pos;
         pos += subIfdSize;
         stripOff[l] = pos;
         pos += pad2(strip[l].length);
      }
      long unitEnd = pos;

      // ---- Pass 2: fill the buffer ----
      ByteBuffer buf = ByteBuffer.allocate((int) (unitEnd - base)).order(order);

      long imageDescEntryAbs = writeLevel0Ifd(buf, (int) (level0IfdOff - base), n0, firstPlane,
            w[0], h[0], stripOff[0], strip[0].length, softwareOff, metaOff, metaBytes,
            subArrayOff, subIfdOff, base);

      putBytes(buf, (int) (softwareOff - base), softwareBytes);
      if (metaBytes != null) {
         putBytes(buf, (int) (metaOff - base), metaBytes);
      }
      if (subArrayOff >= 0) {
         int p = (int) (subArrayOff - base);
         for (int l = 0; l < numLevels - 1; l++) {
            buf.putLong(p + l * 8, subIfdOff[l]);
         }
      }
      putBytes(buf, (int) (stripOff[0] - base), strip[0]);
      for (int l = 1; l < numLevels; l++) {
         writeSubIfd(buf, (int) (subIfdOff[l - 1] - base), w[l], h[l], stripOff[l],
               strip[l].length, l);
         putBytes(buf, (int) (stripOff[l] - base), strip[l]);
      }

      // ---- Write the unit and patch the previous plane's next-IFD pointer ----
      writeAt(base, buf);
      this.writePos = unitEnd;

      long thisNextIfdFieldLoc = level0IfdOff + 8 + (long) n0 * IFD_ENTRY_BYTES;
      if (prevNextIfdFieldLoc >= 0) {
         patchLong(prevNextIfdFieldLoc, level0IfdOff);
      } else {
         patchLong(8, level0IfdOff); // header's first-IFD offset
      }
      prevNextIfdFieldLoc = thisNextIfdFieldLoc;
      if (firstPlane) {
         imageDescEntryLoc = imageDescEntryAbs;
      }

      PlaneLocation loc = new PlaneLocation(numLevels);
      for (int l = 0; l < numLevels; l++) {
         loc.offset[l] = stripOff[l];
         loc.byteCount[l] = strip[l].length;
         loc.width[l] = w[l];
         loc.height[l] = h[l];
      }
      return loc;
   }

   /** Whether the header's first-IFD offset has already been filled (i.e. a plane was written). */
   private boolean headerPatchedFirstIfd() {
      return prevNextIfdFieldLoc >= 0;
   }

   // -------------------------------------------------------------------------
   // Read (safe while writing)
   // -------------------------------------------------------------------------

   /** Read one level's pixels from an already-written plane. Thread-safe with concurrent writes. */
   public Object readLevel(PlaneLocation loc, int level) throws IOException {
      int numPixels = loc.width[level] * loc.height[level];
      ByteBuffer bb = ByteBuffer.allocate((int) loc.byteCount[level]);
      long off = loc.offset[level];
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

   // -------------------------------------------------------------------------
   // Finish
   // -------------------------------------------------------------------------

   /** Write the OME-XML into the first IFD's ImageDescription and finalize the file. */
   public synchronized void finish(byte[] omeXmlUtf8) throws IOException {
      if (finished) {
         return;
      }
      if (headerWritten && imageDescEntryLoc >= 0 && omeXmlUtf8 != null) {
         byte[] descBytes = new byte[omeXmlUtf8.length + 1]; // + NUL terminator
         System.arraycopy(omeXmlUtf8, 0, descBytes, 0, omeXmlUtf8.length);
         long descOff = pad2(writePos);
         ByteBuffer bb = ByteBuffer.allocate((int) (descOff - writePos) + descBytes.length);
         // leading pad byte(s), if any
         for (long i = writePos; i < descOff; i++) {
            bb.put((byte) 0);
         }
         bb.put(descBytes);
         bb.flip();
         writeAt(writePos, bb);
         writePos = descOff + descBytes.length;
         // Patch the ImageDescription entry: count (at +4, LONG8) and value/offset (at +12, LONG8).
         patchLong(imageDescEntryLoc + 4, descBytes.length);
         patchLong(imageDescEntryLoc + 12, descOff);
      }
      // The last plane's next-IFD pointer already holds 0 (buffers are zero-initialised).
      channel.force(true);
      raf.setLength(writePos);
      finished = true;
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
   // IFD writing
   // -------------------------------------------------------------------------

   private static final int SUB_IFD_ENTRIES = 14;

   private int level0EntryCount(boolean firstPlane, boolean hasMeta) {
      // Base grayscale entries: 254,256,257,258,259,262,273,277,278,279,282,283,296,305,339.
      int n = 15;
      if (firstPlane) {
         n += 1; // ImageDescription
      }
      if (numLevels > 1) {
         n += 1; // SubIFDs
      }
      if (hasMeta) {
         n += 1; // MM metadata
      }
      return n;
   }

   /**
    * Write the full-resolution IFD. Returns the absolute file location of the ImageDescription
    * entry (for later patching), or -1 if this IFD has none.
    */
   private long writeLevel0Ifd(ByteBuffer buf, int at, int n0, boolean firstPlane,
                               int w, int h, long stripOffset, int stripBytes,
                               long softwareOff, long metaOff, byte[] metaBytes,
                               long subArrayOff, long[] subIfdOff, long base) {
      buf.putLong(at, n0);
      int e = at + 8;
      long imageDescAbs = -1;

      e = entryLong(buf, e, TAG_NEW_SUBFILE_TYPE, 0);
      e = entryLong(buf, e, TAG_IMAGE_WIDTH, w);
      e = entryLong(buf, e, TAG_IMAGE_LENGTH, h);
      e = entryBitsPerSample(buf, e);
      e = entryShort(buf, e, TAG_COMPRESSION, compression.tiffCode());
      e = entryShort(buf, e, TAG_PHOTOMETRIC, type.photometric());
      if (firstPlane) {
         imageDescAbs = base + e; // absolute file location of this entry (buf index 0 == base)
         e = entryOffset(buf, e, TAG_IMAGE_DESCRIPTION, TYPE_ASCII, 1, 0); // patched at finish()
      }
      e = entryLong8(buf, e, TAG_STRIP_OFFSETS, TYPE_LONG8, stripOffset);
      e = entryShort(buf, e, TAG_SAMPLES_PER_PIXEL, type.samplesPerPixel());
      e = entryLong(buf, e, TAG_ROWS_PER_STRIP, h);
      e = entryLong8(buf, e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, stripBytes);
      e = entryRational(buf, e, TAG_X_RESOLUTION, resNumerator(0), 1);
      e = entryRational(buf, e, TAG_Y_RESOLUTION, resNumerator(0), 1);
      e = entryShort(buf, e, TAG_RESOLUTION_UNIT, 3);
      e = entryOffset(buf, e, TAG_SOFTWARE, TYPE_ASCII, softwareBytes.length, softwareOff);
      if (numLevels > 1) {
         int subCount = numLevels - 1;
         if (subCount == 1) {
            e = entryLong8(buf, e, TAG_SUB_IFDS, TYPE_IFD8, subIfdOff[0]);
         } else {
            e = entryOffset(buf, e, TAG_SUB_IFDS, TYPE_IFD8, subCount, subArrayOff);
         }
      }
      e = entrySampleFormat(buf, e);
      if (metaBytes != null) {
         e = entryOffset(buf, e, TAG_MM_METADATA, TYPE_ASCII, metaBytes.length, metaOff);
      }
      buf.putLong(e, 0L); // next-IFD offset (patched when the following plane is written)
      return imageDescAbs;
   }

   private void writeSubIfd(ByteBuffer buf, int at, int w, int h, long stripOffset,
                            int stripBytes, int level) {
      buf.putLong(at, SUB_IFD_ENTRIES);
      int e = at + 8;
      e = entryLong(buf, e, TAG_NEW_SUBFILE_TYPE, 1); // reduced-resolution
      e = entryLong(buf, e, TAG_IMAGE_WIDTH, w);
      e = entryLong(buf, e, TAG_IMAGE_LENGTH, h);
      e = entryBitsPerSample(buf, e);
      e = entryShort(buf, e, TAG_COMPRESSION, compression.tiffCode());
      e = entryShort(buf, e, TAG_PHOTOMETRIC, type.photometric());
      e = entryLong8(buf, e, TAG_STRIP_OFFSETS, TYPE_LONG8, stripOffset);
      e = entryShort(buf, e, TAG_SAMPLES_PER_PIXEL, type.samplesPerPixel());
      e = entryLong(buf, e, TAG_ROWS_PER_STRIP, h);
      e = entryLong8(buf, e, TAG_STRIP_BYTE_COUNTS, TYPE_LONG8, stripBytes);
      e = entryRational(buf, e, TAG_X_RESOLUTION, resNumerator(level), 1);
      e = entryRational(buf, e, TAG_Y_RESOLUTION, resNumerator(level), 1);
      e = entryShort(buf, e, TAG_RESOLUTION_UNIT, 3);
      e = entrySampleFormat(buf, e);
      buf.putLong(e, 0L); // SubIFDs are not chained
   }

   private long resNumerator(int level) {
      long n = Math.round(pixelsPerCm / Math.pow(2, level));
      return n <= 0 ? 1 : n;
   }

   // -------------------------------------------------------------------------
   // IFD entry primitives (each writes 20 bytes, returns next entry offset)
   // -------------------------------------------------------------------------

   private int entryHeader(ByteBuffer buf, int at, int tag, int type, long count) {
      buf.putShort(at, (short) tag);
      buf.putShort(at + 2, (short) type);
      buf.putLong(at + 4, count);
      // zero the 8-byte value field; callers overwrite the relevant prefix
      buf.putLong(at + 12, 0L);
      return at + 12;
   }

   private int entryShort(ByteBuffer buf, int at, int tag, int value) {
      int v = entryHeader(buf, at, tag, TYPE_SHORT, 1);
      buf.putShort(v, (short) value);
      return at + IFD_ENTRY_BYTES;
   }

   /**
    * A SHORT entry with one value per sample: a scalar for grayscale, or an inline 3-count array
    * for RGB (three SHORTs = 6 bytes fit the 8-byte BigTIFF value field, so no out-of-line data).
    */
   private int entryPerSampleShort(ByteBuffer buf, int at, int tag, int perSampleValue) {
      int spp = type.samplesPerPixel();
      int v = entryHeader(buf, at, tag, TYPE_SHORT, spp);
      for (int i = 0; i < spp; i++) {
         buf.putShort(v + i * 2, (short) perSampleValue);
      }
      return at + IFD_ENTRY_BYTES;
   }

   private int entryBitsPerSample(ByteBuffer buf, int at) {
      return entryPerSampleShort(buf, at, TAG_BITS_PER_SAMPLE, type.bitDepth());
   }

   private int entrySampleFormat(ByteBuffer buf, int at) {
      return entryPerSampleShort(buf, at, TAG_SAMPLE_FORMAT, type.sampleFormat());
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

   /** An entry whose value is an out-of-line offset (ASCII strings, SubIFD arrays). */
   private int entryOffset(ByteBuffer buf, int at, int tag, int type, long count, long offset) {
      int v = entryHeader(buf, at, tag, type, count);
      buf.putLong(v, offset);
      return at + IFD_ENTRY_BYTES;
   }

   // -------------------------------------------------------------------------
   // Low-level helpers
   // -------------------------------------------------------------------------

   private void writeHeader() throws IOException {
      ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES).order(order);
      h.putShort(0, order == ByteOrder.BIG_ENDIAN ? (short) 0x4D4D : (short) 0x4949);
      h.putShort(2, (short) 43);   // BigTIFF version
      h.putShort(4, (short) 8);    // bytesize of offsets
      h.putShort(6, (short) 0);    // reserved
      h.putLong(8, 0L);            // first IFD offset (patched on first plane)
      writeAt(0, h);
      writePos = HEADER_BYTES;
      headerWritten = true;
   }

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

   private static void putBytes(ByteBuffer buf, int at, byte[] data) {
      for (int i = 0; i < data.length; i++) {
         buf.put(at + i, data[i]);
      }
   }

   private static long ifdBytes(int numEntries) {
      return 8L + (long) numEntries * IFD_ENTRY_BYTES + 8L;
   }

   private static long pad2(long v) {
      return (v & 1L) == 0 ? v : v + 1;
   }

   private static int pad2(int v) {
      return (v & 1) == 0 ? v : v + 1;
   }

   private static byte[] asciiz(String s) {
      byte[] raw = s.getBytes(StandardCharsets.UTF_8);
      byte[] out = new byte[raw.length + 1]; // NUL terminator per TIFF ASCII convention
      System.arraycopy(raw, 0, out, 0, raw.length);
      return out;
   }
}
