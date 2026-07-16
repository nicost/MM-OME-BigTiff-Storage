package org.micromanager.mmomebigtiff;

/**
 * 2×2 block-averaging downsampler used to build pyramid levels, adapting the averaging scheme
 * of {@code NDTiffStorage.downsample}. Produces an image of dimensions
 * {@code ceil(width/2) × ceil(height/2)}; odd edges average only the available pixels.
 */
public final class Downsampler {

   private Downsampler() { }

   /** Downsampled width for a given source width. */
   public static int downWidth(int width) {
      return (width + 1) / 2;
   }

   /** Downsampled height for a given source height. */
   public static int downHeight(int height) {
      return (height + 1) / 2;
   }

   /**
    * Downsample a single plane by 2×2 averaging.
    *
    * @param pixels row-major primitive array of {@code width*height} elements
    * @param width  source width
    * @param height source height
    * @param type   pixel type
    * @return a new primitive array of {@code downWidth(width)*downHeight(height)} elements
    */
   public static Object downsample(Object pixels, int width, int height, PixelType type) {
      int dw = downWidth(width);
      int dh = downHeight(height);
      switch (type) {
         case GRAY8:  return downsample8((byte[]) pixels, width, height, dw, dh);
         case GRAY16: return downsample16((short[]) pixels, width, height, dw, dh);
         case GRAY32: return downsample32((float[]) pixels, width, height, dw, dh);
         case RGB8:   return downsampleRgb8((byte[]) pixels, width, height, dw, dh);
         default:     throw new IllegalArgumentException("Unsupported pixel type: " + type);
      }
   }

   // Each variant averages the branch-free 2x2 interior in a tight inner loop and handles the
   // odd right column / bottom row separately, rather than bounds-checking every source pixel.

   private static byte[] downsample8(byte[] src, int w, int h, int dw, int dh) {
      byte[] out = new byte[dw * dh];
      int fullW = w & ~1;
      int fullH = h & ~1;
      for (int dy = 0; dy < fullH / 2; dy++) {
         int r0 = (dy * 2) * w;
         int r1 = r0 + w;
         int o = dy * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            int sum = (src[r0 + sx] & 0xFF) + (src[r0 + sx + 1] & 0xFF)
                  + (src[r1 + sx] & 0xFF) + (src[r1 + sx + 1] & 0xFF);
            out[o++] = (byte) (sum / 4);
         }
         if (fullW < w) {
            out[o] = (byte) (((src[r0 + fullW] & 0xFF) + (src[r1 + fullW] & 0xFF)) / 2);
         }
      }
      if (fullH < h) {
         int r0 = fullH * w;
         int o = (dh - 1) * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            out[o++] = (byte) (((src[r0 + sx] & 0xFF) + (src[r0 + sx + 1] & 0xFF)) / 2);
         }
         if (fullW < w) {
            out[o] = src[r0 + fullW];
         }
      }
      return out;
   }

   /**
    * Downsample interleaved 8-bit RGB ({@code byte[w*h*3]}) by 2×2 averaging, each of the three
    * channels averaged independently. Same interior/odd-edge scheme as {@link #downsample8}, but
    * with a 3-sample stride.
    */
   private static byte[] downsampleRgb8(byte[] src, int w, int h, int dw, int dh) {
      final int spp = 3;
      byte[] out = new byte[dw * dh * spp];
      int fullW = w & ~1;
      int fullH = h & ~1;
      for (int dy = 0; dy < fullH / 2; dy++) {
         int r0 = (dy * 2) * w * spp;
         int r1 = r0 + w * spp;
         int o = dy * dw * spp;
         for (int sx = 0; sx < fullW; sx += 2) {
            int c0 = r0 + sx * spp;
            int c1 = r1 + sx * spp;
            for (int k = 0; k < spp; k++) {
               int sum = (src[c0 + k] & 0xFF) + (src[c0 + spp + k] & 0xFF)
                     + (src[c1 + k] & 0xFF) + (src[c1 + spp + k] & 0xFF);
               out[o + k] = (byte) (sum / 4);
            }
            o += spp;
         }
         if (fullW < w) {
            int c0 = r0 + fullW * spp;
            int c1 = r1 + fullW * spp;
            for (int k = 0; k < spp; k++) {
               out[o + k] = (byte) (((src[c0 + k] & 0xFF) + (src[c1 + k] & 0xFF)) / 2);
            }
         }
      }
      if (fullH < h) {
         int r0 = fullH * w * spp;
         int o = (dh - 1) * dw * spp;
         for (int sx = 0; sx < fullW; sx += 2) {
            int c0 = r0 + sx * spp;
            for (int k = 0; k < spp; k++) {
               out[o + k] = (byte) (((src[c0 + k] & 0xFF) + (src[c0 + spp + k] & 0xFF)) / 2);
            }
            o += spp;
         }
         if (fullW < w) {
            int c0 = r0 + fullW * spp;
            for (int k = 0; k < spp; k++) {
               out[o + k] = src[c0 + k];
            }
         }
      }
      return out;
   }

   private static short[] downsample16(short[] src, int w, int h, int dw, int dh) {
      short[] out = new short[dw * dh];
      int fullW = w & ~1;
      int fullH = h & ~1;
      for (int dy = 0; dy < fullH / 2; dy++) {
         int r0 = (dy * 2) * w;
         int r1 = r0 + w;
         int o = dy * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            int sum = (src[r0 + sx] & 0xFFFF) + (src[r0 + sx + 1] & 0xFFFF)
                  + (src[r1 + sx] & 0xFFFF) + (src[r1 + sx + 1] & 0xFFFF);
            out[o++] = (short) (sum / 4);
         }
         if (fullW < w) {
            out[o] = (short) (((src[r0 + fullW] & 0xFFFF) + (src[r1 + fullW] & 0xFFFF)) / 2);
         }
      }
      if (fullH < h) {
         int r0 = fullH * w;
         int o = (dh - 1) * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            out[o++] = (short) (((src[r0 + sx] & 0xFFFF) + (src[r0 + sx + 1] & 0xFFFF)) / 2);
         }
         if (fullW < w) {
            out[o] = src[r0 + fullW];
         }
      }
      return out;
   }

   private static float[] downsample32(float[] src, int w, int h, int dw, int dh) {
      float[] out = new float[dw * dh];
      int fullW = w & ~1;
      int fullH = h & ~1;
      for (int dy = 0; dy < fullH / 2; dy++) {
         int r0 = (dy * 2) * w;
         int r1 = r0 + w;
         int o = dy * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            double sum = (double) src[r0 + sx] + src[r0 + sx + 1]
                  + src[r1 + sx] + src[r1 + sx + 1];
            out[o++] = (float) (sum / 4);
         }
         if (fullW < w) {
            out[o] = (float) (((double) src[r0 + fullW] + src[r1 + fullW]) / 2);
         }
      }
      if (fullH < h) {
         int r0 = fullH * w;
         int o = (dh - 1) * dw;
         for (int sx = 0; sx < fullW; sx += 2) {
            out[o++] = (float) (((double) src[r0 + sx] + src[r0 + sx + 1]) / 2);
         }
         if (fullW < w) {
            out[o] = src[r0 + fullW];
         }
      }
      return out;
   }
}
