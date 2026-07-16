package org.micromanager.mmomebigtiff.tiff;

import org.micromanager.mmomebigtiff.PixelType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reopens an existing pyramidal OME-BigTIFF file written by {@link TiffPyramidWriter} (or any
 * Bio-Formats-style pyramidal OME-TIFF using SubIFDs), reconstructing:
 *
 * <ul>
 *   <li>the pixel type, byte order and Z/C/T sizes from the first IFD's OME-XML;</li>
 *   <li>a {@link PlaneLocation} for every full-resolution plane (level 0) and its SubIFD pyramid;
 *       and</li>
 *   <li>the mapping from each {@code (z,c,t)} coordinate to its {@link PlaneLocation}, from the
 *       OME-XML {@code TiffData} records.</li>
 * </ul>
 *
 * <p>The open file channel is retained so pixels can be read on demand; call {@link #close} when
 * finished.
 */
public final class TiffPyramidReader implements AutoCloseable {

   private final RandomAccessFile raf;
   private final FileChannel channel;
   private final ByteOrder order;
   private final PixelType type;
   private final int sizeX;
   private final int sizeY;
   private final int sizeZ;
   private final int sizeC;
   private final int sizeT;
   private final int numLevels;
   private final Map<String, PlaneLocation> byZct;

   private TiffPyramidReader(RandomAccessFile raf, FileChannel channel, ByteOrder order,
                             PixelType type, int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
                             int numLevels, Map<String, PlaneLocation> byZct) {
      this.raf = raf;
      this.channel = channel;
      this.order = order;
      this.type = type;
      this.sizeX = sizeX;
      this.sizeY = sizeY;
      this.sizeZ = sizeZ;
      this.sizeC = sizeC;
      this.sizeT = sizeT;
      this.numLevels = numLevels;
      this.byZct = byZct;
   }

   public ByteOrder order() {
      return order;
   }

   public PixelType type() {
      return type;
   }

   public int sizeX() {
      return sizeX;
   }

   public int sizeY() {
      return sizeY;
   }

   public int sizeZ() {
      return sizeZ;
   }

   public int sizeC() {
      return sizeC;
   }

   public int sizeT() {
      return sizeT;
   }

   public int numLevels() {
      return numLevels;
   }

   /** Location of the plane at (z,c,t), or null if that coordinate has no stored plane. */
   public PlaneLocation location(int z, int c, int t) {
      return byZct.get(zctKey(z, c, t));
   }

   /** Read one pyramid level of a plane into a primitive pixel array. */
   public Object readLevel(PlaneLocation loc, int level, boolean deflate) throws IOException {
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
      byte[] raw = deflate
            ? TiffPixelCodec.inflate(onDisk, TiffPixelCodec.rawByteCount(type, numPixels))
            : onDisk;
      return TiffPixelCodec.fromRawBytes(raw, type, order, numPixels);
   }

   /** Read one tile (full {@code tileWidth*tileHeight}) of a tiled plane's level. */
   public Object readTile(PlaneLocation loc, int level, int tileCol, int tileRow, boolean deflate)
         throws IOException {
      int numPixels = loc.tileWidth * loc.tileHeight;
      int tileIndex = tileRow * loc.tilesAcross[level] + tileCol;
      long off = loc.tileOffsets[level][tileIndex];
      long count = loc.tileByteCounts[level][tileIndex];
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
      byte[] raw = deflate
            ? TiffPixelCodec.inflate(onDisk, TiffPixelCodec.rawByteCount(type, numPixels))
            : onDisk;
      return TiffPixelCodec.fromRawBytes(raw, type, order, numPixels);
   }

   /** Read an arbitrary region of a tiled plane's level, touching only the covering tiles. */
   public Object readRegion(PlaneLocation loc, int level, long x, long y, int w, int h,
                            boolean deflate) throws IOException {
      return Tiles.readRegion((lvl, tc, tr) -> readTile(loc, lvl, tc, tr, deflate),
            type, level, loc.tileWidth, loc.tileHeight, loc.width[level], loc.height[level],
            x, y, w, h);
   }

   @Override
   public void close() {
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

   public static String zctKey(int z, int c, int t) {
      return z + "/" + c + "/" + t;
   }

   // -------------------------------------------------------------------------
   // Open / parse
   // -------------------------------------------------------------------------

   public static TiffPyramidReader open(Path file) throws IOException {
      RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
      FileChannel channel = raf.getChannel();
      try {
         ByteBuffer head = read(channel, 0, 16);
         short bo = head.getShort(0);
         ByteOrder order = bo == 0x4D4D ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
         head.order(order);
         int version = head.getShort(2) & 0xFFFF;
         boolean bigTiff = version == 43;

         long firstIfd;
         if (bigTiff) {
            firstIfd = head.getLong(8);
         } else {
            firstIfd = head.getInt(4) & 0xFFFFFFFFL;
         }

         List<PlaneLocation> planesByIfd = new ArrayList<>();
         String omeXml = null;
         long ifdOff = firstIfd;
         boolean first = true;
         while (ifdOff != 0) {
            Ifd ifd = readIfd(channel, ifdOff, order, bigTiff);
            if (first) {
               omeXml = ifd.imageDescription;
               first = false;
            }
            planesByIfd.add(planeFromIfd(channel, ifd, order, bigTiff));
            ifdOff = ifd.nextIfd;
         }

         if (omeXml == null) {
            throw new IOException("No OME-XML ImageDescription in first IFD of " + file);
         }
         Ome ome = parseOme(omeXml);

         Map<String, PlaneLocation> byZct = new HashMap<>();
         for (int[] td : ome.tiffData) {
            int ifd = td[0];
            int z = td[1];
            int c = td[2];
            int t = td[3];
            if (ifd >= 0 && ifd < planesByIfd.size()) {
               byZct.put(zctKey(z, c, t), planesByIfd.get(ifd));
            }
         }
         int numLevels = planesByIfd.isEmpty() ? 1 : planesByIfd.get(0).numLevels();

         TiffPyramidReader r = new TiffPyramidReader(raf, channel, order, ome.type,
               ome.sizeX, ome.sizeY, ome.sizeZ, ome.sizeC, ome.sizeT, numLevels, byZct);
         return r;
      } catch (IOException e) {
         channel.close();
         raf.close();
         throw e;
      } catch (RuntimeException e) {
         channel.close();
         raf.close();
         throw e;
      }
   }

   private static PlaneLocation planeFromIfd(FileChannel channel, Ifd ifd, ByteOrder order,
                                             boolean bigTiff) throws IOException {
      List<Ifd> levels = new ArrayList<>();
      levels.add(ifd);
      for (long subOff : ifd.subIfds) {
         levels.add(readIfd(channel, subOff, order, bigTiff));
      }
      if (ifd.tileWidth > 0) {
         PlaneLocation loc = PlaneLocation.tiled(levels.size(), ifd.tileWidth, ifd.tileHeight);
         for (int l = 0; l < levels.size(); l++) {
            Ifd li = levels.get(l);
            loc.width[l] = (int) li.width;
            loc.height[l] = (int) li.height;
            loc.tilesAcross[l] = (int) ((li.width + ifd.tileWidth - 1) / ifd.tileWidth);
            loc.tilesDown[l] = (int) ((li.height + ifd.tileHeight - 1) / ifd.tileHeight);
            loc.tileOffsets[l] = li.tileOffsets;
            loc.tileByteCounts[l] = li.tileByteCounts;
         }
         return loc;
      }
      PlaneLocation loc = new PlaneLocation(levels.size());
      for (int l = 0; l < levels.size(); l++) {
         Ifd li = levels.get(l);
         loc.offset[l] = li.stripOffset;
         loc.byteCount[l] = li.stripByteCount;
         loc.width[l] = (int) li.width;
         loc.height[l] = (int) li.height;
      }
      return loc;
   }

   // -------------------------------------------------------------------------
   // IFD parsing
   // -------------------------------------------------------------------------

   private static final class Ifd {
      long width;
      long height;
      long stripOffset;
      long stripByteCount;
      long[] subIfds = new long[0];
      long nextIfd;
      String imageDescription;
      // Tiled planes (TIFF tags 322-325); tileWidth == 0 means untiled (single strip).
      int tileWidth;
      int tileHeight;
      long[] tileOffsets = new long[0];
      long[] tileByteCounts = new long[0];
   }

   private static Ifd readIfd(FileChannel channel, long off, ByteOrder order, boolean bigTiff)
         throws IOException {
      Ifd ifd = new Ifd();
      long numEntries;
      int entrySize;
      int headerSize;
      if (bigTiff) {
         numEntries = read(channel, off, 8).order(order).getLong(0);
         entrySize = 20;
         headerSize = 8;
      } else {
         numEntries = read(channel, off, 2).order(order).getShort(0) & 0xFFFF;
         entrySize = 12;
         headerSize = 2;
      }
      long entriesStart = off + headerSize;
      ByteBuffer entries = read(channel, entriesStart, (int) (numEntries * entrySize)).order(order);
      for (int i = 0; i < numEntries; i++) {
         int base = i * entrySize;
         int tag = entries.getShort(base) & 0xFFFF;
         int type = entries.getShort(base + 2) & 0xFFFF;
         long count = bigTiff ? entries.getLong(base + 4)
               : (entries.getInt(base + 4) & 0xFFFFFFFFL);
         int valuePos = base + (bigTiff ? 12 : 8);
         switch (tag) {
            case 256:
               ifd.width = readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 257:
               ifd.height = readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 273:
               ifd.stripOffset = readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 279:
               ifd.stripByteCount = readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 330:
               ifd.subIfds = readOffsets(channel, entries, valuePos, count, order, bigTiff);
               break;
            case 270:
               ifd.imageDescription = readAscii(channel, entries, valuePos, count, order, bigTiff);
               break;
            case 322:
               ifd.tileWidth = (int) readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 323:
               ifd.tileHeight = (int) readScalar(channel, entries, valuePos, type, order, bigTiff);
               break;
            case 324:
               ifd.tileOffsets = readOffsets(channel, entries, valuePos, count, order, bigTiff);
               break;
            case 325:
               ifd.tileByteCounts = readOffsets(channel, entries, valuePos, count, order, bigTiff);
               break;
            default:
               break;
         }
      }
      ByteBuffer next = read(channel, entriesStart + numEntries * entrySize, bigTiff ? 8 : 4)
            .order(order);
      ifd.nextIfd = bigTiff ? next.getLong(0) : (next.getInt(0) & 0xFFFFFFFFL);
      return ifd;
   }

   private static long readScalar(FileChannel channel, ByteBuffer entries, int valuePos, int type,
                                  ByteOrder order, boolean bigTiff) throws IOException {
      // Single-count value stored inline in the value field.
      switch (type) {
         case 3: // SHORT
            return entries.getShort(valuePos) & 0xFFFF;
         case 4: // LONG
            return entries.getInt(valuePos) & 0xFFFFFFFFL;
         case 16: // LONG8
         case 18: // IFD8
            return entries.getLong(valuePos);
         default:
            return entries.getInt(valuePos) & 0xFFFFFFFFL;
      }
   }

   private static long[] readOffsets(FileChannel channel, ByteBuffer entries, int valuePos,
                                     long count, ByteOrder order, boolean bigTiff)
         throws IOException {
      int elemSize = bigTiff ? 8 : 4;
      long inlineCap = bigTiff ? 8 : 4;
      long[] out = new long[(int) count];
      if (count * elemSize <= inlineCap) {
         for (int i = 0; i < count; i++) {
            out[i] = bigTiff ? entries.getLong(valuePos + i * 8)
                  : (entries.getInt(valuePos + i * 4) & 0xFFFFFFFFL);
         }
         return out;
      }
      long dataOff = bigTiff ? entries.getLong(valuePos) : (entries.getInt(valuePos) & 0xFFFFFFFFL);
      ByteBuffer data = read(channel, dataOff, (int) (count * elemSize)).order(order);
      for (int i = 0; i < count; i++) {
         out[i] = bigTiff ? data.getLong(i * 8) : (data.getInt(i * 4) & 0xFFFFFFFFL);
      }
      return out;
   }

   private static String readAscii(FileChannel channel, ByteBuffer entries, int valuePos,
                                   long count, ByteOrder order, boolean bigTiff) throws IOException {
      long inlineCap = bigTiff ? 8 : 4;
      byte[] bytes;
      if (count <= inlineCap) {
         bytes = new byte[(int) count];
         for (int i = 0; i < count; i++) {
            bytes[i] = entries.get(valuePos + i);
         }
      } else {
         long dataOff = bigTiff ? entries.getLong(valuePos)
               : (entries.getInt(valuePos) & 0xFFFFFFFFL);
         bytes = read(channel, dataOff, (int) count).array();
      }
      int len = bytes.length;
      while (len > 0 && bytes[len - 1] == 0) {
         len--;
      }
      return new String(bytes, 0, len, StandardCharsets.UTF_8);
   }

   private static ByteBuffer read(FileChannel channel, long pos, int len) throws IOException {
      ByteBuffer bb = ByteBuffer.allocate(len);
      while (bb.hasRemaining()) {
         int n = channel.read(bb, pos + bb.position());
         if (n < 0) {
            break;
         }
      }
      bb.rewind();
      return bb;
   }

   // -------------------------------------------------------------------------
   // OME-XML parsing (JDK DOM; no extra dependency)
   // -------------------------------------------------------------------------

   private static final class Ome {
      PixelType type;
      int sizeX;
      int sizeY;
      int sizeZ = 1;
      int sizeC = 1;
      int sizeT = 1;
      final List<int[]> tiffData = new ArrayList<>(); // {ifd, z, c, t}
   }

   private static Ome parseOme(String xml) throws IOException {
      try {
         DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
         f.setNamespaceAware(false);
         DocumentBuilder db = f.newDocumentBuilder();
         Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
         Element pixels = firstElement(doc.getElementsByTagName("Pixels"));
         if (pixels == null) {
            throw new IOException("OME-XML has no Pixels element");
         }
         Ome ome = new Ome();
         // SamplesPerPixel lives on Pixels (or, for older writers, on the first Channel); it is
         // what distinguishes RGB (3) from grayscale (1), since RGB8 shares Type="uint8".
         int spp = intAttr(pixels, "SamplesPerPixel", 0);
         if (spp <= 0) {
            Element ch = firstElement(pixels.getElementsByTagName("Channel"));
            spp = ch == null ? 1 : intAttr(ch, "SamplesPerPixel", 1);
         }
         ome.type = PixelType.fromOme(pixels.getAttribute("Type"), spp);
         ome.sizeX = intAttr(pixels, "SizeX", 0);
         ome.sizeY = intAttr(pixels, "SizeY", 0);
         ome.sizeZ = intAttr(pixels, "SizeZ", 1);
         ome.sizeC = intAttr(pixels, "SizeC", 1);
         ome.sizeT = intAttr(pixels, "SizeT", 1);

         NodeList tds = pixels.getElementsByTagName("TiffData");
         if (tds.getLength() > 0) {
            for (int i = 0; i < tds.getLength(); i++) {
               Element td = (Element) tds.item(i);
               int ifd = intAttr(td, "IFD", 0);
               int z = intAttr(td, "FirstZ", 0);
               int c = intAttr(td, "FirstC", 0);
               int t = intAttr(td, "FirstT", 0);
               ome.tiffData.add(new int[]{ifd, z, c, t});
            }
         } else {
            // No explicit TiffData: enumerate planes in DimensionOrder (default XYZCT).
            String dimOrder = pixels.getAttribute("DimensionOrder");
            if (dimOrder == null || dimOrder.isEmpty()) {
               dimOrder = "XYZCT";
            }
            enumeratePlanes(ome, dimOrder);
         }
         return ome;
      } catch (IOException e) {
         throw e;
      } catch (Exception e) {
         throw new IOException("Failed to parse OME-XML", e);
      }
   }

   private static void enumeratePlanes(Ome ome, String dimOrder) {
      // The three non-XY dimension letters, slowest-varying last.
      char[] dims = new char[3];
      int di = 0;
      for (char ch : dimOrder.toCharArray()) {
         if (ch == 'Z' || ch == 'C' || ch == 'T') {
            dims[di++] = ch;
         }
      }
      int[] sizes = new int[3];
      for (int i = 0; i < 3; i++) {
         sizes[i] = dims[i] == 'Z' ? ome.sizeZ : dims[i] == 'C' ? ome.sizeC : ome.sizeT;
      }
      int ifd = 0;
      for (int i2 = 0; i2 < sizes[2]; i2++) {
         for (int i1 = 0; i1 < sizes[1]; i1++) {
            for (int i0 = 0; i0 < sizes[0]; i0++) {
               int z = 0;
               int c = 0;
               int t = 0;
               int[] idx = {i0, i1, i2};
               for (int k = 0; k < 3; k++) {
                  if (dims[k] == 'Z') {
                     z = idx[k];
                  } else if (dims[k] == 'C') {
                     c = idx[k];
                  } else {
                     t = idx[k];
                  }
               }
               ome.tiffData.add(new int[]{ifd++, z, c, t});
            }
         }
      }
   }

   private static Element firstElement(NodeList list) {
      for (int i = 0; i < list.getLength(); i++) {
         Node n = list.item(i);
         if (n instanceof Element) {
            return (Element) n;
         }
      }
      return null;
   }

   private static int intAttr(Element e, String name, int dflt) {
      String v = e.getAttribute(name);
      if (v == null || v.isEmpty()) {
         return dflt;
      }
      try {
         return Integer.parseInt(v.trim());
      } catch (NumberFormatException ex) {
         return dflt;
      }
   }
}
