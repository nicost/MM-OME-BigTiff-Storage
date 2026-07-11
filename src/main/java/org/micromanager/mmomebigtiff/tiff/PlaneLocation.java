package org.micromanager.mmomebigtiff.tiff;

/**
 * Records where a single multi-resolution plane's pixel strips live inside an OME-BigTIFF file:
 * one file offset, on-disk byte count and spatial extent per pyramid level (level 0 = full
 * resolution). Populated by {@link TiffPyramidWriter} as planes are written and by
 * {@link TiffPyramidReader} when an existing file is reopened; used by the storage's read path
 * to fetch pixels without re-parsing the IFDs.
 */
public final class PlaneLocation {

   /** File offset of each level's pixel strip (index 0 = full resolution). */
   public final long[] offset;
   /** On-disk byte count of each level's strip (compressed length if compressed). */
   public final long[] byteCount;
   /** Pixel width of each level. */
   public final int[] width;
   /** Pixel height of each level. */
   public final int[] height;

   // --- Tiled planes only (null/false for the single-strip layout) ---------------------------

   /** Whether this plane is stored as tiles (TIFF tags 322–325) rather than one strip. */
   public final boolean tiled;
   /** Tile width/height in pixels (same for every level). */
   public final int tileWidth;
   public final int tileHeight;
   /** Tile-grid dimensions of each level. */
   public final int[] tilesAcross;
   public final int[] tilesDown;
   /** Per-level, per-tile file offset and on-disk byte count (row-major tile order). */
   public final long[][] tileOffsets;
   public final long[][] tileByteCounts;

   public PlaneLocation(int numLevels) {
      this.offset = new long[numLevels];
      this.byteCount = new long[numLevels];
      this.width = new int[numLevels];
      this.height = new int[numLevels];
      this.tiled = false;
      this.tileWidth = 0;
      this.tileHeight = 0;
      this.tilesAcross = null;
      this.tilesDown = null;
      this.tileOffsets = null;
      this.tileByteCounts = null;
   }

   private PlaneLocation(int numLevels, int tileWidth, int tileHeight) {
      this.offset = new long[numLevels];
      this.byteCount = new long[numLevels];
      this.width = new int[numLevels];
      this.height = new int[numLevels];
      this.tiled = true;
      this.tileWidth = tileWidth;
      this.tileHeight = tileHeight;
      this.tilesAcross = new int[numLevels];
      this.tilesDown = new int[numLevels];
      this.tileOffsets = new long[numLevels][];
      this.tileByteCounts = new long[numLevels][];
   }

   /** Create a tiled plane location; callers fill the per-level geometry and tile arrays. */
   public static PlaneLocation tiled(int numLevels, int tileWidth, int tileHeight) {
      return new PlaneLocation(numLevels, tileWidth, tileHeight);
   }

   public int numLevels() {
      return offset.length;
   }
}
