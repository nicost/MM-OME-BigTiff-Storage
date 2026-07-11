package org.micromanager.mmomebigtiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end write/read tests for {@link OMEBigTiffStorage}. */
public class RoundTripTest {

   private static short[] ramp16(int w, int h, int seed) {
      short[] p = new short[w * h];
      for (int i = 0; i < p.length; i++) {
         p[i] = (short) ((i + seed * 7) & 0xFFFF);
      }
      return p;
   }

   private static Map<String, Object> axes(int t, int c, int z) {
      Map<String, Object> a = new HashMap<>();
      a.put("time", t);
      a.put("channel", c);
      a.put("z", z);
      return a;
   }

   @Test
   void writeReadSinglePlaneNoPyramid(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build())
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).build())
            .addAxis(AxisInfo.builder("z").type(DimensionType.SPACE).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "acq", "{\"foo\":1}", cfg);
      int w = 64;
      int h = 48;
      short[] pix = ramp16(w, h, 3);
      store.putImage(pix, "{\"n\":42}", axes(0, 0, 0), false, 16, h, w);
      store.finishedWriting();

      OMEBigTiffImage img = store.getImage(axes(0, 0, 0));
      assertNotNull(img);
      assertArrayEquals(pix, (short[]) img.pix);
      assertEquals("{\"n\":42}", img.metadataJson);
      assertEquals("{\"foo\":1}", store.getSummaryMetadata());
      assertTrue(store.hasImage(axes(0, 0, 0)));
      assertFalse(store.hasImage(axes(1, 0, 0)));
      store.close();
   }

   @Test
   void multiDimensionalPyramidRoundTrip(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .numResolutionLevels(3)
            .pixelSize(0.325)
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build())
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL)
                  .channels(Arrays.asList(Channel.builder("DAPI").color("#0000FF").build(),
                        Channel.builder("FITC").color("#00FF00").build())).build())
            .addAxis(AxisInfo.builder("z").type(DimensionType.SPACE).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "acq", null, cfg);

      int w = 100;
      int h = 60;
      Map<String, short[]> written = new HashMap<>();
      for (int t = 0; t < 2; t++) {
         for (int c = 0; c < 2; c++) {
            for (int z = 0; z < 3; z++) {
               short[] pix = ramp16(w, h, t * 100 + c * 10 + z);
               written.put(t + "/" + c + "/" + z, pix);
               store.putImage(pix, null, axes(t, c, z), false, 16, h, w);
            }
         }
      }
      store.finishedWriting();

      assertEquals(3, store.getNumResLevels());
      // Full-resolution pixels round-trip exactly.
      for (Map.Entry<String, short[]> e : written.entrySet()) {
         String[] k = e.getKey().split("/");
         OMEBigTiffImage img = store.getImage(
               axes(Integer.parseInt(k[0]), Integer.parseInt(k[1]), Integer.parseInt(k[2])));
         assertNotNull(img, "missing " + e.getKey());
         assertArrayEquals(e.getValue(), (short[]) img.pix);
      }
      // Downsampled levels have the expected reduced size and match a fresh downsample.
      OMEBigTiffImage lvl1 = store.getImage(axes(0, 0, 0), 1);
      assertNotNull(lvl1);
      short[] expected1 = (short[]) Downsampler.downsample(written.get("0/0/0"), w, h, PixelType.GRAY16);
      assertArrayEquals(expected1, (short[]) lvl1.pix);
      EssentialImageMetadata em1 = store.getEssentialImageMetadata(axes(0, 0, 0), 1);
      assertEquals(Downsampler.downWidth(w), em1.getWidth());
      assertEquals(Downsampler.downHeight(h), em1.getHeight());
      store.close();

      // Reopen and verify pixels + pyramid are still readable.
      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      assertEquals(3, re.getNumResLevels());
      OMEBigTiffImage r000 = re.getImage(axes(0, 0, 0));
      assertArrayEquals(written.get("0/0/0"), (short[]) r000.pix);
      OMEBigTiffImage r112 = re.getImage(axes(1, 1, 2));
      assertArrayEquals(written.get("1/1/2"), (short[]) r112.pix);
      OMEBigTiffImage rl2 = re.getImage(axes(1, 0, 1), 2);
      assertNotNull(rl2);
      assertEquals(12, re.getAxesSet().size());
      re.close();
   }

   @Test
   void multiPositionSeparateFiles(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .numResolutionLevels(2)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "multi", null, cfg);
      int w = 40;
      int h = 40;
      for (int p = 0; p < 3; p++) {
         for (int c = 0; c < 2; c++) {
            Map<String, Object> a = new HashMap<>();
            a.put("position", p);
            a.put("channel", c);
            store.putImage(ramp16(w, h, p * 10 + c), null, a, false, 16, h, w);
         }
      }
      store.finishedWriting();
      String location = store.getDiskLocation();
      store.close();

      Path p0 = Paths.get(location, "multi_p0.ome.tif");
      Path p2 = Paths.get(location, "multi_p2.ome.tif");
      assertTrue(p0.toFile().exists());
      assertTrue(p2.toFile().exists());

      OMEBigTiffStorage re = OMEBigTiffStorage.load(location);
      Map<String, Object> a = new HashMap<>();
      a.put("position", 2);
      a.put("channel", 1);
      OMEBigTiffImage img = re.getImage(a);
      assertNotNull(img);
      assertArrayEquals(ramp16(w, h, 21), (short[]) img.pix);
      re.close();
   }

   @Test
   void readDuringWriteFromPendingBuffer(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig().numResolutionLevels(2)
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "live", null, cfg);
      int w = 32;
      int h = 32;
      Map<String, Object> a = new HashMap<>();
      a.put("time", 0);
      short[] pix = ramp16(w, h, 1);
      store.putImage(pix, null, a, false, 16, h, w);
      // Immediately readable (from pending buffer or disk) before finishedWriting.
      OMEBigTiffImage img = store.getImage(a);
      assertNotNull(img);
      assertArrayEquals(pix, (short[]) img.pix);
      // Synthesized downsample also available immediately.
      OMEBigTiffImage lvl1 = store.getImage(a, 1);
      assertNotNull(lvl1);
      assertEquals(Downsampler.downWidth(w) * Downsampler.downHeight(h), ((short[]) lvl1.pix).length);
      store.close();
   }

   @Test
   void gray8AndDeflate(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .numResolutionLevels(2)
            .compression(Compression.DEFLATE)
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "gray8", null, cfg);
      int w = 50;
      int h = 30;
      byte[] pix = new byte[w * h];
      for (int i = 0; i < pix.length; i++) {
         pix[i] = (byte) (i & 0xFF);
      }
      Map<String, Object> a = new HashMap<>();
      a.put("channel", 0);
      store.putImage(pix, null, a, false, 8, h, w);
      store.finishedWriting();
      OMEBigTiffImage img = store.getImage(a);
      assertArrayEquals(pix, (byte[]) img.pix);
      store.close();

      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      assertArrayEquals(pix, (byte[]) re.getImage(a).pix);
      re.close();
   }

   @Test
   void stringChannelValuesRoundTrip(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .addAxis(AxisInfo.builder("channel").type(DimensionType.CHANNEL).build())
            .addAxis(AxisInfo.builder("z").type(DimensionType.SPACE).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "strchan", null, cfg);
      int w = 32;
      int h = 24;
      String[] channels = {"DAPI", "FITC"};
      Map<String, short[]> written = new HashMap<>();
      for (int c = 0; c < channels.length; c++) {
         for (int z = 0; z < 2; z++) {
            short[] pix = ramp16(w, h, c * 10 + z);
            written.put(channels[c] + "/" + z, pix);
            Map<String, Object> a = new HashMap<>();
            a.put("channel", channels[c]);
            a.put("z", z);
            store.putImage(pix, null, a, false, 16, h, w);
         }
      }
      store.finishedWriting();
      store.close();

      // Every (string channel, z) must come back as its own distinct plane after reopening —
      // string axis positions map to stable integer C indices in the OME-XML TiffData records.
      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      for (String ch : channels) {
         for (int z = 0; z < 2; z++) {
            Map<String, Object> a = new HashMap<>();
            a.put("channel", ch);
            a.put("z", z);
            OMEBigTiffImage img = re.getImage(a);
            assertNotNull(img, "missing " + ch + "/z" + z);
            assertArrayEquals(written.get(ch + "/" + z), (short[]) img.pix, ch + "/z" + z);
         }
      }
      re.close();
   }

   @Test
   void nominalBitDepthPreserved(@TempDir Path dir) {
      OMEBigTiffStorageConfig cfg = new OMEBigTiffStorageConfig()
            .addAxis(AxisInfo.builder("time").type(DimensionType.TIME).build());
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "bits12", null, cfg);
      int w = 16;
      int h = 16;
      Map<String, Object> a = new HashMap<>();
      a.put("time", 0);
      // A 12-bit camera image: stored as GRAY16 but the nominal bit depth must survive.
      store.putImage(ramp16(w, h, 0), null, a, false, 12, h, w);
      assertEquals(12, store.getEssentialImageMetadata(a).getBitDepth());
      store.finishedWriting();
      store.close();

      OMEBigTiffStorage re = OMEBigTiffStorage.load(store.getDiskLocation());
      assertEquals(12, re.getEssentialImageMetadata(a).getBitDepth());
      re.close();
   }

   @Test
   void missingImageReturnsNull(@TempDir Path dir) {
      OMEBigTiffStorage store = new OMEBigTiffStorage(dir.toString(), "empty", null,
            new OMEBigTiffStorageConfig().addAxis(
                  AxisInfo.builder("time").type(DimensionType.TIME).build()));
      Map<String, Object> a = new HashMap<>();
      a.put("time", 0);
      store.putImage(ramp16(16, 16, 0), null, a, false, 16, 16, 16);
      store.finishedWriting();
      Map<String, Object> missing = new HashMap<>();
      missing.put("time", 99);
      assertNull(store.getImage(missing));
      store.close();
   }
}
