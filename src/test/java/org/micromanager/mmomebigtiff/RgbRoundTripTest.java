package org.micromanager.mmomebigtiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.micromanager.mmomebigtiff.tiff.TiffPixelCodec;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write/read tests for 8-bit RGB, covering the whole-plane and tiled paths. */
public class RgbRoundTripTest {

   /** Micro-Manager-style 4-byte/pixel BGRA gradient (alpha filled but unused). */
   private static byte[] bgra(int w, int h, int seed) {
      byte[] p = new byte[w * h * 4];
      for (int i = 0; i < w * h; i++) {
         int s = i * 4;
         p[s]     = (byte) ((i + seed) & 0xFF);        // B
         p[s + 1] = (byte) ((i * 2 + seed) & 0xFF);    // G
         p[s + 2] = (byte) ((i * 3 + seed) & 0xFF);    // R
         p[s + 3] = (byte) 0xFF;                        // A (unused)
      }
      return p;
   }

   /** The expected on-disk / read-back 3-byte interleaved RGB for {@link #bgra}. */
   private static byte[] expectedRgb(int w, int h, int seed) {
      byte[] p = new byte[w * h * 3];
      for (int i = 0; i < w * h; i++) {
         int d = i * 3;
         p[d]     = (byte) ((i * 3 + seed) & 0xFF);    // R
         p[d + 1] = (byte) ((i * 2 + seed) & 0xFF);    // G
         p[d + 2] = (byte) ((i + seed) & 0xFF);        // B
      }
      return p;
   }

   private static Map<String, Object> axes(int c) {
      Map<String, Object> a = new HashMap<>();
      a.put("time", c);
      return a;
   }

   @Test
   void packBgraToRgbPinsChannelOrder() {
      // Two pixels: (B,G,R,A) = (1,2,3,255) and (10,20,30,255).
      byte[] in = {1, 2, 3, (byte) 255, 10, 20, 30, (byte) 255};
      byte[] out = TiffPixelCodec.packBgraToRgb(in, 2);
      assertArrayEquals(new byte[]{3, 2, 1, 30, 20, 10}, out);
   }

   @Test
   void rgb8WholePlaneRoundTrip(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "rgb", null, cfg);
      int w = 40;
      int h = 24;
      store.putImage(bgra(w, h, 7), "{\"n\":1}", axes(0), true, 8, h, w);
      store.finishedWriting();

      OMEBigTiffImage img = store.getImage(axes(0));
      assertNotNull(img);
      assertArrayEquals(expectedRgb(w, h, 7), (byte[]) img.pix);
      EssentialImageMetadata em = store.getEssentialImageMetadata(axes(0));
      assertTrue(em.isRGB());
      assertEquals(8, em.getBitDepth());
      assertEquals(w, em.getWidth());
      assertEquals(h, em.getHeight());
      store.close();

      // Reopen: RGB is reconstructed from the descriptor + OME SamplesPerPixel.
      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      OMEBigTiffImage r = re.getImage(axes(0));
      assertNotNull(r);
      assertArrayEquals(expectedRgb(w, h, 7), (byte[]) r.pix);
      assertTrue(re.getEssentialImageMetadata(axes(0)).isRGB());
      re.close();
   }

   @Test
   void rgb8PyramidPerChannelAverage(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .numResolutionLevels(2)
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "rgbpyr", null, cfg);
      int w = 32;
      int h = 16;
      store.putImage(bgra(w, h, 3), null, axes(0), true, 8, h, w);
      store.finishedWriting();

      byte[] baseRgb = expectedRgb(w, h, 3);
      byte[] expLvl1 = (byte[]) Downsampler.downsample(baseRgb, w, h, PixelType.RGB8);
      OMEBigTiffImage lvl1 = store.getImage(axes(0), 1);
      assertNotNull(lvl1);
      assertArrayEquals(expLvl1, (byte[]) lvl1.pix);
      // Sanity: level-1 has ceil(w/2)*ceil(h/2)*3 samples.
      assertEquals(Downsampler.downWidth(w) * Downsampler.downHeight(h) * 3,
            ((byte[]) lvl1.pix).length);
      store.close();
   }

   @Test
   void rgb8TiledRegionRoundTrip(@TempDir Path dir) {
      long cw = 96;
      long ch = 64;
      int tw = 32;
      int th = 32;
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .fullPlaneSize(cw, ch)
            .tileSize(tw, th)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).count(1).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "rgbtiled", null, cfg);
      Map<String, Object> a = new HashMap<>();
      a.put("channel", 0);

      // A canvas-wide RGB pattern: sample value derived from absolute (x,y), per channel.
      int across = (int) ((cw + tw - 1) / tw);
      int down = (int) ((ch + th - 1) / th);
      for (int tr = 0; tr < down; tr++) {
         for (int tc = 0; tc < across; tc++) {
            byte[] tile = new byte[tw * th * 4]; // BGRA in
            for (int ty = 0; ty < th; ty++) {
               for (int tx = 0; tx < tw; tx++) {
                  long x = (long) tc * tw + tx;
                  long y = (long) tr * th + ty;
                  int s = (ty * tw + tx) * 4;
                  boolean inside = x < cw && y < ch;
                  tile[s]     = (byte) (inside ? (x & 0xFF) : 0);        // B
                  tile[s + 1] = (byte) (inside ? (y & 0xFF) : 0);        // G
                  tile[s + 2] = (byte) (inside ? ((x + y) & 0xFF) : 0);  // R
                  tile[s + 3] = (byte) 0xFF;
               }
            }
            store.putTile(tile, null, a, tc, tr, true, 8);
         }
      }
      store.finishedWriting();

      // Read a region spanning tiles and check the RGB (on-disk order R,G,B) values.
      long rx = 20;
      long ry = 10;
      int rw = 50;
      int rh = 40;
      OMEBigTiffImage reg = store.getRegion(a, 0, rx, ry, rw, rh);
      assertNotNull(reg);
      assertArrayEquals(refRgbRegion(rx, ry, rw, rh), (byte[]) reg.pix);
      store.close();

      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      OMEBigTiffImage reg2 = re.getRegion(a, 0, rx, ry, rw, rh);
      assertNotNull(reg2);
      assertArrayEquals(refRgbRegion(rx, ry, rw, rh), (byte[]) reg2.pix);
      assertTrue(re.getEssentialImageMetadata(a).isRGB());
      re.close();
   }

   /** Expected 3-byte RGB region for the tiled pattern above. */
   private static byte[] refRgbRegion(long x, long y, int w, int h) {
      byte[] r = new byte[w * h * 3];
      for (int j = 0; j < h; j++) {
         for (int i = 0; i < w; i++) {
            long ax = x + i;
            long ay = y + j;
            int d = (j * w + i) * 3;
            r[d]     = (byte) ((ax + ay) & 0xFF); // R
            r[d + 1] = (byte) (ay & 0xFF);        // G
            r[d + 2] = (byte) (ax & 0xFF);        // B
         }
      }
      return r;
   }
}
