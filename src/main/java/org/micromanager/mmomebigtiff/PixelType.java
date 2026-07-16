package org.micromanager.mmomebigtiff;

/**
 * Pixel formats the storage understands, and their mapping to OME-XML {@code Pixels/@Type} and
 * TIFF {@code SampleFormat} / {@code SamplesPerPixel} / {@code PhotometricInterpretation}.
 *
 * <p>Supports single-component grayscale in 8-, 16- and 32-bit forms, plus 8-bit interleaved
 * 3-sample {@link #RGB8 RGB}. {@code bytesPerPixel()} is bytes per <em>sample</em> (a component);
 * a pixel occupies {@code bytesPerPixel() * samplesPerPixel()} bytes on disk.
 */
public enum PixelType {
   GRAY8("uint8", 1, 1, PixelType.SAMPLE_FORMAT_UINT, PixelType.PHOTOMETRIC_MIN_IS_BLACK),
   GRAY16("uint16", 2, 1, PixelType.SAMPLE_FORMAT_UINT, PixelType.PHOTOMETRIC_MIN_IS_BLACK),
   GRAY32("float", 4, 1, PixelType.SAMPLE_FORMAT_FLOAT, PixelType.PHOTOMETRIC_MIN_IS_BLACK),
   /** 8-bit interleaved RGB: a {@code byte[]} of {@code width*height*3}, samples ordered R,G,B. */
   RGB8("uint8", 1, 3, PixelType.SAMPLE_FORMAT_UINT, PixelType.PHOTOMETRIC_RGB);

   /** TIFF SampleFormat tag value for unsigned integer samples. */
   public static final int SAMPLE_FORMAT_UINT = 1;
   /** TIFF SampleFormat tag value for IEEE floating-point samples. */
   public static final int SAMPLE_FORMAT_FLOAT = 3;
   /** TIFF PhotometricInterpretation tag value for grayscale (0 = black). */
   public static final int PHOTOMETRIC_MIN_IS_BLACK = 1;
   /** TIFF PhotometricInterpretation tag value for RGB colour. */
   public static final int PHOTOMETRIC_RGB = 2;

   private final String omeType;
   private final int bytesPerPixel;
   private final int samplesPerPixel;
   private final int sampleFormat;
   private final int photometric;

   PixelType(String omeType, int bytesPerPixel, int samplesPerPixel, int sampleFormat,
             int photometric) {
      this.omeType = omeType;
      this.bytesPerPixel = bytesPerPixel;
      this.samplesPerPixel = samplesPerPixel;
      this.sampleFormat = sampleFormat;
      this.photometric = photometric;
   }

   /** OME-XML {@code Pixels/@Type} string, e.g. {@code "uint16"}. */
   public String omeType() {
      return omeType;
   }

   /** Bytes per <em>sample</em> (one colour component), e.g. 2 for {@code uint16}, 1 for RGB8. */
   public int bytesPerPixel() {
      return bytesPerPixel;
   }

   /** Samples (colour components) per pixel: 1 for grayscale, 3 for RGB. */
   public int samplesPerPixel() {
      return samplesPerPixel;
   }

   /** Total bytes one pixel occupies on disk ({@code bytesPerPixel() * samplesPerPixel()}). */
   public int bytesPerPixelPacked() {
      return bytesPerPixel * samplesPerPixel;
   }

   /** Whether this is a multi-component (RGB) format. */
   public boolean isRgb() {
      return samplesPerPixel > 1;
   }

   public int sampleFormat() {
      return sampleFormat;
   }

   /** TIFF {@code PhotometricInterpretation} tag value (1 = grayscale, 2 = RGB). */
   public int photometric() {
      return photometric;
   }

   public int bitDepth() {
      return bytesPerPixel * 8;
   }

   /**
    * Resolve a pixel type from the {@code (rgb, bitDepth)} pair used by the {@code putImage}
    * API (which mirrors NDTiffStorage's signature).
    *
    * @param rgb      whether the image has multiple colour components
    * @param bitDepth nominal bits per component (8, 16 or 32)
    */
   public static PixelType of(boolean rgb, int bitDepth) {
      if (rgb) {
         // Micro-Manager delivers RGB as 8-bit-per-component (the adapter reports bitDepth == 8);
         // only 8-bit RGB is representable as chunky RGB TIFF here.
         if (bitDepth != 8) {
            throw new IllegalArgumentException("Only 8-bit RGB is supported (got bitDepth="
                  + bitDepth + "); use grayscale for higher bit depths.");
         }
         return RGB8;
      }
      if (bitDepth <= 8) {
         return GRAY8;
      }
      if (bitDepth <= 16) {
         return GRAY16;
      }
      if (bitDepth <= 32) {
         return GRAY32;
      }
      throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
   }

   /**
    * Resolve a pixel type from an OME-XML {@code Pixels/@Type} string (e.g. {@code "uint16"}),
    * assuming a single sample per pixel (grayscale). For RGB, use {@link #fromOme(String, int)}.
    *
    * @throws IllegalArgumentException for types this library cannot represent, rather than
    *         silently mis-reading the data
    */
   public static PixelType fromOmeType(String type) {
      return fromOme(type, 1);
   }

   /**
    * Resolve a pixel type from an OME-XML {@code Pixels/@Type} string and its
    * {@code SamplesPerPixel}. RGB shares the {@code "uint8"} type string with {@link #GRAY8}, so
    * the sample count is what distinguishes them.
    *
    * @param type             OME pixel type string (e.g. {@code "uint16"}, {@code "float"})
    * @param samplesPerPixel  components per pixel (1 for grayscale, 3 for RGB)
    * @throws IllegalArgumentException for types this library cannot represent
    */
   public static PixelType fromOme(String type, int samplesPerPixel) {
      if (samplesPerPixel >= 3) {
         if ("uint8".equalsIgnoreCase(type)) {
            return RGB8;
         }
         throw new IllegalArgumentException("Unsupported RGB OME pixel type: " + type
               + " with SamplesPerPixel=" + samplesPerPixel + " (only 8-bit RGB is supported)");
      }
      for (PixelType t : values()) {
         if (t.samplesPerPixel == 1 && t.omeType.equalsIgnoreCase(type)) {
            return t;
         }
      }
      // OME-XML uses "float" for 32-bit float; also accept "float32" defensively.
      if ("float32".equalsIgnoreCase(type)) {
         return GRAY32;
      }
      throw new IllegalArgumentException("Unsupported OME pixel type: " + type
            + " (supported: uint8, uint16, float)");
   }

   /** Number of pixels represented by a primitive pixel array of this type. */
   public int pixelCount(Object pixels) {
      switch (this) {
         case GRAY8:  return ((byte[]) pixels).length;
         case GRAY16: return ((short[]) pixels).length;
         case GRAY32: return ((float[]) pixels).length;
         case RGB8:   return ((byte[]) pixels).length / 3;
         default:     throw new IllegalStateException();
      }
   }
}
