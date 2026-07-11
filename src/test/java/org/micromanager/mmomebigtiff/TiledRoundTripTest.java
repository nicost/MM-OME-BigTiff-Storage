package org.micromanager.mmomebigtiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tiled OME-BigTIFF write/region-read tests, including a &gt;2-gigapixel canvas. */
public class TiledRoundTripTest {

   private static short val(long x, long y) {
      return (short) ((x * 31 + y * 17) & 0xFFFF);
   }

   /** Reference region [x,x+w) x [y,y+h) at full resolution. */
   private static short[] refRegion(long x, long y, int w, int h) {
      short[] r = new short[w * h];
      for (int j = 0; j < h; j++) {
         for (int i = 0; i < w; i++) {
            r[j * w + i] = val(x + i, y + j);
         }
      }
      return r;
   }

   /** Write every tile of one plane, zero-padding tiles that spill past the canvas. */
   private static void writePlaneTiles(OMEBigTiffStorage store, Map<String, Object> axes,
                                       long canvasW, long canvasH, int tileW, int tileH) {
      int across = (int) ((canvasW + tileW - 1) / tileW);
      int down = (int) ((canvasH + tileH - 1) / tileH);
      for (int tr = 0; tr < down; tr++) {
         for (int tc = 0; tc < across; tc++) {
            short[] tile = new short[tileW * tileH];
            for (int ty = 0; ty < tileH; ty++) {
               for (int tx = 0; tx < tileW; tx++) {
                  long x = (long) tc * tileW + tx;
                  long y = (long) tr * tileH + ty;
                  tile[ty * tileW + tx] = (x < canvasW && y < canvasH) ? val(x, y) : 0;
               }
            }
            store.putTile(tile, null, axes, tc, tr, false, 16);
         }
      }
   }

   @Test
   void tiledSinglePlaneRegionAndWholeReadRoundTrip(@TempDir Path dir) {
      long cw = 200;
      long ch = 150;
      int tw = 64;
      int th = 64;
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .fullPlaneSize(cw, ch)
            .tileSize(tw, th)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).count(1).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "tiled", null, cfg);
      Map<String, Object> a = new HashMap<>();
      a.put("channel", 0);
      writePlaneTiles(store, a, cw, ch, tw, th);
      store.finishedWriting();

      // Region spanning several tiles.
      OMEBigTiffImage reg = store.getRegion(a, 0, 30, 40, 120, 90);
      assertNotNull(reg);
      assertArrayEquals(refRegion(30, 40, 120, 90), (short[]) reg.pix);
      // Edge region touching the bottom-right corner.
      OMEBigTiffImage edge = store.getRegion(a, 0, 190, 140, 10, 10);
      assertArrayEquals(refRegion(190, 140, 10, 10), (short[]) edge.pix);
      // Whole plane via getImage (fits an array).
      OMEBigTiffImage whole = store.getImage(a);
      assertArrayEquals(refRegion(0, 0, (int) cw, (int) ch), (short[]) whole.pix);
      EssentialImageMetadata em = store.getEssentialImageMetadata(a);
      assertEquals((int) cw, em.getWidth());
      assertEquals((int) ch, em.getHeight());
      store.close();

      // Reopen and re-read from the parsed tiled IFDs.
      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      OMEBigTiffImage reg2 = re.getRegion(a, 0, 30, 40, 120, 90);
      assertNotNull(reg2);
      assertArrayEquals(refRegion(30, 40, 120, 90), (short[]) reg2.pix);
      assertArrayEquals(refRegion(0, 0, (int) cw, (int) ch), (short[]) re.getImage(a).pix);
      re.close();
   }

   @Test
   void tiledMultiPlaneDistinctPlanes(@TempDir Path dir) {
      long cw = 130;
      long ch = 100;
      int tw = 48; // multiple of 16
      int th = 32;
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .fullPlaneSize(cw, ch)
            .tileSize(tw, th)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).count(2).build())
            .addAxis(AxisInfo.builder("z").type(DimensionType.SPACE).count(2).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "multi", null, cfg);
      // Offset each plane's pattern so planes are distinguishable.
      for (int c = 0; c < 2; c++) {
         for (int z = 0; z < 2; z++) {
            final int off = 1000 * c + 100 * z;
            Map<String, Object> a = new HashMap<>();
            a.put("channel", c);
            a.put("z", z);
            int across = (int) ((cw + tw - 1) / tw);
            int down = (int) ((ch + th - 1) / th);
            for (int tr = 0; tr < down; tr++) {
               for (int tc = 0; tc < across; tc++) {
                  short[] tile = new short[tw * th];
                  for (int ty = 0; ty < th; ty++) {
                     for (int tx = 0; tx < tw; tx++) {
                        long x = (long) tc * tw + tx;
                        long y = (long) tr * th + ty;
                        tile[ty * tw + tx] = (x < cw && y < ch) ? (short) ((val(x, y) + off) & 0xFFFF) : 0;
                     }
                  }
                  store.putTile(tile, null, a, tc, tr, false, 16);
               }
            }
         }
      }
      store.finishedWriting();
      store.close();

      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      assertEquals(4, re.getAxesSet().size());
      for (int c = 0; c < 2; c++) {
         for (int z = 0; z < 2; z++) {
            int off = 1000 * c + 100 * z;
            Map<String, Object> a = new HashMap<>();
            a.put("channel", c);
            a.put("z", z);
            OMEBigTiffImage img = re.getRegion(a, 0, 10, 10, 40, 40);
            assertNotNull(img, "missing c" + c + " z" + z);
            short[] expected = new short[40 * 40];
            for (int j = 0; j < 40; j++) {
               for (int i = 0; i < 40; i++) {
                  expected[j * 40 + i] = (short) ((val(10 + i, 10 + j) + off) & 0xFFFF);
               }
            }
            assertArrayEquals(expected, (short[]) img.pix, "c" + c + " z" + z);
         }
      }
      re.close();
   }

   @Test
   void hugeCanvasBeyondArrayLimit(@TempDir Path dir) {
      long cw = 100_000;
      long ch = 100_000; // 1e10 px, ~47x the max Java array length
      int tw = 512;
      int th = 512;
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .fullPlaneSize(cw, ch)
            .tileSize(tw, th)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).count(1).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "huge", null, cfg);
      Map<String, Object> a = new HashMap<>();
      a.put("channel", 0);
      assertTrue(cw * ch > Integer.MAX_VALUE, "canvas must exceed the array limit for this test");

      // Write just a few scattered tiles; peak memory stays tile-sized.
      int[][] tiles = {{0, 0}, {50, 60}, {195, 195}};
      for (int[] t : tiles) {
         int tc = t[0];
         int tr = t[1];
         short[] tile = new short[tw * th];
         for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
               tile[ty * tw + tx] = val((long) tc * tw + tx, (long) tr * th + ty);
            }
         }
         store.putTile(tile, null, a, tc, tr, false, 16);
      }
      store.finishedWriting();

      // A small region inside a written tile reads back correctly.
      long rx = 50L * tw + 100;
      long ry = 60L * th + 200;
      OMEBigTiffImage reg = store.getRegion(a, 0, rx, ry, 64, 48);
      assertArrayEquals(refRegion(rx, ry, 64, 48), (short[]) reg.pix);
      // Whole-plane read is refused with a clear message.
      IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.getImage(a));
      assertTrue(ex.getMessage().contains("getRegion"), ex.getMessage());
      store.close();
   }
}
