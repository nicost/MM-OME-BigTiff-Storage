package org.micromanager.mmomebigtiff;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a new {@link OMEBigTiffStorage} dataset.
 *
 * <p>All fields have sensible defaults; the common case is
 * {@code new OMEBigTiffStorageConfig()} plus perhaps a channel/pixel-size description for richer
 * OME metadata. Non-position axes may be declared up front (bounded, best metadata) or
 * discovered dynamically from the first {@code putImage} call.
 */
public final class OMEBigTiffStorageConfig {

   /**
    * Ordered non-position axes (excluding the trailing spatial y/x, which are implicit).
    * If empty, the axis set and order are inferred from the first image's axes map, using
    * the canonical order (time, channel, z). Each axis must map to an OME-XML dimension
    * (time→T, channel→C, z/space→Z); at most one axis per dimension.
    */
   private List<AxisInfo> axes = new ArrayList<>();

   /** Name of the axis that maps to separate OME-BigTIFF files (one file per position). */
   private String positionAxis = "position";

   /** Physical pixel size for the y and x axes (in {@link #spatialUnit}). */
   private double pixelSizeY = 1.0;
   private double pixelSizeX = 1.0;
   private String spatialUnit = "micrometer";

   private Compression compression = Compression.NONE;

   /** Number of pyramid resolution levels (1 = no pyramid). Each level halves y and x. */
   private int numResolutionLevels = 1;

   /**
    * Full plane (canvas) width/height in pixels for tiled mode. Zero (the default) means
    * untiled: each plane is written as one strip and its size is taken from the first image.
    * When both are &gt; 0 the writer switches to tiled OME-BigTIFF: every plane spans a fixed
    * canvas of this size, images are written as {@code tileWidth}×{@code tileHeight} tiles, and
    * readers can fetch sub-regions without reading the whole plane. Required for planes larger
    * than a Java array can hold (~2.1 gigapixels).
    */
   private long fullPlaneWidth = 0;
   private long fullPlaneHeight = 0;

   /** Tile dimensions for tiled mode (TIFF requires multiples of 16). */
   private int tileWidth = 512;
   private int tileHeight = 512;

   /** If true, {@code putImage} auto-computes and writes all pyramid levels by 2x2 averaging. */
   private boolean autoDownsample = true;

   /** Bounded size of the writer queue; a full queue applies back-pressure to producers. */
   private int savingQueueSize = 50;

   public List<AxisInfo> getAxes() {
      return axes;
   }

   public OMEBigTiffStorageConfig axes(List<AxisInfo> axes) {
      this.axes = new ArrayList<>(axes);
      return this;
   }

   public OMEBigTiffStorageConfig addAxis(AxisInfo axis) {
      this.axes.add(axis);
      return this;
   }

   public String getPositionAxis() {
      return positionAxis;
   }

   public OMEBigTiffStorageConfig positionAxis(String positionAxis) {
      this.positionAxis = positionAxis;
      return this;
   }

   public double getPixelSizeY() {
      return pixelSizeY;
   }

   public double getPixelSizeX() {
      return pixelSizeX;
   }

   public OMEBigTiffStorageConfig pixelSize(double sizeYAndX) {
      this.pixelSizeY = sizeYAndX;
      this.pixelSizeX = sizeYAndX;
      return this;
   }

   public OMEBigTiffStorageConfig pixelSize(double sizeY, double sizeX) {
      this.pixelSizeY = sizeY;
      this.pixelSizeX = sizeX;
      return this;
   }

   public String getSpatialUnit() {
      return spatialUnit;
   }

   public OMEBigTiffStorageConfig spatialUnit(String spatialUnit) {
      this.spatialUnit = spatialUnit;
      return this;
   }

   public Compression getCompression() {
      return compression;
   }

   public OMEBigTiffStorageConfig compression(Compression compression) {
      this.compression = compression;
      return this;
   }

   public int getNumResolutionLevels() {
      return numResolutionLevels;
   }

   public OMEBigTiffStorageConfig numResolutionLevels(int levels) {
      if (levels < 1) {
         throw new IllegalArgumentException("numResolutionLevels must be >= 1");
      }
      this.numResolutionLevels = levels;
      return this;
   }

   public boolean isAutoDownsample() {
      return autoDownsample;
   }

   public OMEBigTiffStorageConfig autoDownsample(boolean autoDownsample) {
      this.autoDownsample = autoDownsample;
      return this;
   }

   public int getSavingQueueSize() {
      return savingQueueSize;
   }

   public OMEBigTiffStorageConfig savingQueueSize(int savingQueueSize) {
      this.savingQueueSize = savingQueueSize;
      return this;
   }

   public long getFullPlaneWidth() {
      return fullPlaneWidth;
   }

   public long getFullPlaneHeight() {
      return fullPlaneHeight;
   }

   /**
    * Declare the full plane (canvas) size and switch to tiled OME-BigTIFF. Both dimensions must
    * be positive.
    */
   public OMEBigTiffStorageConfig fullPlaneSize(long width, long height) {
      if (width <= 0 || height <= 0) {
         throw new IllegalArgumentException(
               "fullPlaneSize dimensions must be positive, got " + width + "x" + height);
      }
      this.fullPlaneWidth = width;
      this.fullPlaneHeight = height;
      return this;
   }

   public int getTileWidth() {
      return tileWidth;
   }

   public int getTileHeight() {
      return tileHeight;
   }

   /** Set the tile dimensions for tiled mode. TIFF requires each to be a positive multiple of 16. */
   public OMEBigTiffStorageConfig tileSize(int width, int height) {
      if (width <= 0 || height <= 0 || (width % 16) != 0 || (height % 16) != 0) {
         throw new IllegalArgumentException(
               "tileSize dimensions must be positive multiples of 16, got " + width + "x" + height);
      }
      this.tileWidth = width;
      this.tileHeight = height;
      return this;
   }

   /** Whether tiled output is enabled (a full plane size has been declared). */
   public boolean isTiled() {
      return fullPlaneWidth > 0 && fullPlaneHeight > 0;
   }
}
