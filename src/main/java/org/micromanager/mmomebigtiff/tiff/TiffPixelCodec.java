package org.micromanager.mmomebigtiff.tiff;

import org.micromanager.mmomebigtiff.PixelType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Converts primitive pixel arrays ({@code byte[]}/{@code short[]}/{@code float[]}) to and from
 * the raw byte representation stored in a TIFF strip, applying the file's byte order and, when
 * requested, Deflate (zlib) compression (TIFF compression code 8).
 */
public final class TiffPixelCodec {

   private TiffPixelCodec() { }

   /** Raw (uncompressed) byte length of a plane of {@code numPixels} pixels of the given type. */
   public static int rawByteCount(PixelType type, int numPixels) {
      return numPixels * type.bytesPerPixelPacked();
   }

   /** Encode a primitive pixel array to raw little/big-endian bytes (no compression). */
   public static byte[] toRawBytes(Object pixels, PixelType type, ByteOrder order) {
      switch (type) {
         case GRAY8:
         case RGB8: {
            // Both are byte[]; RGB8 samples are already interleaved R,G,B (one byte each), so
            // byte order does not apply.
            byte[] p = (byte[]) pixels;
            return p.clone();
         }
         case GRAY16: {
            short[] p = (short[]) pixels;
            ByteBuffer bb = ByteBuffer.allocate(p.length * 2).order(order);
            bb.asShortBuffer().put(p);
            return bb.array();
         }
         case GRAY32: {
            float[] p = (float[]) pixels;
            ByteBuffer bb = ByteBuffer.allocate(p.length * 4).order(order);
            bb.asFloatBuffer().put(p);
            return bb.array();
         }
         default:
            throw new IllegalArgumentException("Unsupported pixel type: " + type);
      }
   }

   /** Decode raw bytes (matching {@link #toRawBytes}) back to a primitive pixel array. */
   public static Object fromRawBytes(byte[] raw, PixelType type, ByteOrder order, int numPixels) {
      switch (type) {
         case GRAY8:
         case RGB8: {
            int len = numPixels * type.samplesPerPixel(); // byte[] length (RGB8: 3 bytes/pixel)
            if (raw.length == len) {
               return raw;
            }
            byte[] out = new byte[len];
            System.arraycopy(raw, 0, out, 0, Math.min(len, raw.length));
            return out;
         }
         case GRAY16: {
            short[] out = new short[numPixels];
            ByteBuffer.wrap(raw).order(order).asShortBuffer().get(out);
            return out;
         }
         case GRAY32: {
            float[] out = new float[numPixels];
            ByteBuffer.wrap(raw).order(order).asFloatBuffer().get(out);
            return out;
         }
         default:
            throw new IllegalArgumentException("Unsupported pixel type: " + type);
      }
   }

   /** A zeroed primitive pixel array of {@code numPixels} pixels of the given type. */
   public static Object blankTile(PixelType type, int numPixels) {
      switch (type) {
         case GRAY8:  return new byte[numPixels];
         case GRAY16: return new short[numPixels];
         case GRAY32: return new float[numPixels];
         case RGB8:   return new byte[numPixels * 3];
         default:     throw new IllegalArgumentException("Unsupported pixel type: " + type);
      }
   }

   /**
    * Copy a {@code w}×{@code h} rectangle between two row-major primitive pixel arrays of the same
    * type, for single-sample-per-pixel (grayscale) data. Rows are copied with
    * {@link System#arraycopy}; callers ensure the rectangle lies within both arrays.
    */
   public static void copyRegion(Object src, int srcW, int srcX, int srcY,
                                 Object dst, int dstW, int dstX, int dstY, int w, int h) {
      copyRegion(src, srcW, srcX, srcY, dst, dstW, dstX, dstY, w, h, 1);
   }

   /**
    * Copy a {@code w}×{@code h} pixel rectangle between two row-major arrays whose pixels each
    * span {@code samplesPerPixel} array elements (1 for grayscale, 3 for interleaved RGB). All
    * widths/offsets are in <em>pixels</em>; the copy scales them by {@code samplesPerPixel}.
    */
   public static void copyRegion(Object src, int srcW, int srcX, int srcY,
                                 Object dst, int dstW, int dstX, int dstY, int w, int h,
                                 int samplesPerPixel) {
      int spp = samplesPerPixel;
      for (int row = 0; row < h; row++) {
         int s = ((srcY + row) * srcW + srcX) * spp;
         int d = ((dstY + row) * dstW + dstX) * spp;
         System.arraycopy(src, s, dst, d, w * spp);
      }
   }

   /**
    * Unpack Micro-Manager's native 4-bytes-per-pixel RGB buffer (interleaved <b>BGRA</b>, alpha
    * unused) into interleaved 3-sample RGB ({@code byte[numPixels*3]}, samples ordered R,G,B) as
    * stored on disk. This is the single place the Micro-Manager channel order is encoded; if the
    * assumption is wrong, only the index mapping below changes.
    *
    * @param bgra      source of length {@code >= numPixels*4}
    * @param numPixels number of pixels
    */
   public static byte[] packBgraToRgb(byte[] bgra, int numPixels) {
      byte[] rgb = new byte[numPixels * 3];
      for (int i = 0; i < numPixels; i++) {
         int s = i * 4;
         int d = i * 3;
         byte b = bgra[s];
         byte g = bgra[s + 1];
         byte r = bgra[s + 2];
         rgb[d] = r;
         rgb[d + 1] = g;
         rgb[d + 2] = b;
      }
      return rgb;
   }

   /** Deflate (zlib) compression of a raw strip. */
   public static byte[] deflate(byte[] raw) {
      Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
      deflater.setInput(raw);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
      byte[] buf = new byte[8192];
      while (!deflater.finished()) {
         int n = deflater.deflate(buf);
         out.write(buf, 0, n);
      }
      deflater.end();
      return out.toByteArray();
   }

   /** Inverse of {@link #deflate}; {@code rawLength} is the known uncompressed size. */
   public static byte[] inflate(byte[] compressed, int rawLength) {
      Inflater inflater = new Inflater();
      inflater.setInput(compressed);
      byte[] out = new byte[rawLength];
      try {
         int total = 0;
         while (total < rawLength && !inflater.finished()) {
            int n = inflater.inflate(out, total, rawLength - total);
            if (n == 0 && inflater.needsInput()) {
               break;
            }
            total += n;
         }
      } catch (DataFormatException e) {
         throw new IllegalStateException("Corrupt Deflate strip", e);
      } finally {
         inflater.end();
      }
      return out;
   }
}
