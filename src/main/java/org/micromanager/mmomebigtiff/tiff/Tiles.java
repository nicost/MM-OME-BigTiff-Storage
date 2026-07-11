package org.micromanager.mmomebigtiff.tiff;

import org.micromanager.mmomebigtiff.PixelType;

import java.io.IOException;

/**
 * Assembles an arbitrary pixel rectangle from a tiled plane by reading only the tiles that
 * overlap the requested region and cropping them into the output buffer. Shared by the tiled
 * writer (read-back / pyramid) and reader (reopened files) so region logic lives in one place.
 */
public final class Tiles {

   private Tiles() { }

   /** Supplies one full {@code tileWidth*tileHeight} tile of a level. */
   public interface TileReader {
      Object read(int level, int tileCol, int tileRow) throws IOException;
   }

   /**
    * Read the {@code w}×{@code h} region whose top-left corner is ({@code x},{@code y}) at the
    * given pyramid level. The region must lie within the level's canvas.
    *
    * @return a primitive pixel array of {@code w*h} samples of the plane's type
    */
   public static Object readRegion(TileReader src, PixelType type, int level,
                                   int tileWidth, int tileHeight,
                                   long levelWidth, long levelHeight,
                                   long x, long y, int w, int h) throws IOException {
      if (x < 0 || y < 0 || w <= 0 || h <= 0
            || x + w > levelWidth || y + h > levelHeight) {
         throw new IndexOutOfBoundsException("region (" + x + "," + y + " " + w + "x" + h
               + ") outside level canvas " + levelWidth + "x" + levelHeight);
      }
      Object out = TiffPixelCodec.blankTile(type, w * h);
      int firstCol = (int) (x / tileWidth);
      int lastCol = (int) ((x + w - 1) / tileWidth);
      int firstRow = (int) (y / tileHeight);
      int lastRow = (int) ((y + h - 1) / tileHeight);
      for (int tr = firstRow; tr <= lastRow; tr++) {
         long tileY0 = (long) tr * tileHeight;
         for (int tc = firstCol; tc <= lastCol; tc++) {
            long tileX0 = (long) tc * tileWidth;
            // Overlap of this tile with the requested region, in absolute pixel coords.
            long ox0 = Math.max(x, tileX0);
            long oy0 = Math.max(y, tileY0);
            long ox1 = Math.min(x + w, tileX0 + tileWidth);
            long oy1 = Math.min(y + h, tileY0 + tileHeight);
            int ow = (int) (ox1 - ox0);
            int oh = (int) (oy1 - oy0);
            if (ow <= 0 || oh <= 0) {
               continue;
            }
            Object tile = src.read(level, tc, tr);
            int srcX = (int) (ox0 - tileX0);
            int srcY = (int) (oy0 - tileY0);
            int dstX = (int) (ox0 - x);
            int dstY = (int) (oy0 - y);
            TiffPixelCodec.copyRegion(tile, tileWidth, srcX, srcY, out, w, dstX, dstY, ow, oh);
         }
      }
      return out;
   }
}
