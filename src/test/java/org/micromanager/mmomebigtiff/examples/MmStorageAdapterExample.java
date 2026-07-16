package org.micromanager.mmomebigtiff.examples;

import org.micromanager.mmomebigtiff.EssentialImageMetadata;
import org.micromanager.mmomebigtiff.OMEBigTiffImage;
import org.micromanager.mmomebigtiff.OMEBigTiffStorage;
import org.micromanager.mmomebigtiff.OMEBigTiffStorageConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Illustrative Micro-Manager {@code Storage} adapter, compiled against the small stub
 * interfaces below rather than the real Micro-Manager classes so this project stays free of any
 * Micro-Manager dependency. The real adapter would implement
 * {@code org.micromanager.data.Storage} and reuse exactly this conversion logic.
 *
 * <p>Key points mirrored from the existing {@code NDTiffAdapter}:
 * <ul>
 *   <li>{@code Coords} &lt;-&gt; axes map ({@code axisName -> Integer}); Micro-Manager omits
 *       zero-valued axes, so absent axes are treated as index 0.</li>
 *   <li>Per-image {@code Metadata} and {@code SummaryMetadata} travel as JSON strings — exactly
 *       the neutral boundary {@link OMEBigTiffStorage} exposes.</li>
 *   <li>The underlying store can only be constructed once summary metadata is available, so the
 *       adapter defers creation until {@link #setSummaryMetadata} is called (Micro-Manager
 *       delivers this via a Datastore event).</li>
 *   <li>The adapter only ever uses resolution level 0 (Micro-Manager's Datastore is
 *       single-resolution), even though the library writes a full pyramid.</li>
 * </ul>
 */
public final class MmStorageAdapterExample {

   // --- Minimal stand-ins for the Micro-Manager SPI (illustrative only) ------------------

   /** Stand-in for {@code org.micromanager.data.Coords}. */
   public interface MmCoords {
      Iterable<String> getAxes();

      int getIndex(String axis); // -1 if absent
   }

   /** Stand-in for {@code org.micromanager.data.Image}. */
   public interface MmImage {
      Object getRawPixels();     // byte[]/short[]/float[]

      int getWidth();

      int getHeight();

      int getBytesPerPixel();

      int getNumComponents();

      MmCoords getCoords();

      String getMetadataJson();
   }

   // --- Adapter -------------------------------------------------------------------------

   private final String dir;
   private final String name;
   private final OMEBigTiffStorageConfig config;
   private OMEBigTiffStorage storage; // created lazily once summary metadata arrives

   public MmStorageAdapterExample(String dir, String name, OMEBigTiffStorageConfig config) {
      this.dir = dir;
      this.name = name;
      this.config = config;
   }

   /** Micro-Manager delivers summary metadata before any image; only then can we create storage. */
   public void setSummaryMetadata(String summaryJson) {
      if (storage == null) {
         storage = new OMEBigTiffStorage(dir, name, summaryJson, config);
      }
   }

   public void putImage(MmImage image) {
      if (storage == null) {
         throw new IllegalStateException("setSummaryMetadata must be called before putImage");
      }
      Map<String, Object> axes = coordsToAxes(image.getCoords());
      boolean rgb = image.getNumComponents() > 1;
      // For RGB, Micro-Manager reports 3 components in a 4-byte pixel; the library stores 8-bit
      // RGB and expects bitDepth == 8. The raw byte[] is passed through as-is (4-byte BGRA); the
      // library unpacks it to 3-sample RGB on write.
      int bitDepth = rgb ? 8
            : image.getBytesPerPixel() / Math.max(1, image.getNumComponents()) * 8;
      storage.putImage(image.getRawPixels(), image.getMetadataJson(), axes,
            rgb, bitDepth, image.getHeight(), image.getWidth());
   }

   public OMEBigTiffImage getImage(MmCoords coords) {
      return storage.getImage(coordsToAxes(coords));
   }

   public EssentialImageMetadata getEssentialImageMetadata(MmCoords coords) {
      return storage.getEssentialImageMetadata(coordsToAxes(coords));
   }

   public boolean hasImage(MmCoords coords) {
      return storage.hasImage(coordsToAxes(coords));
   }

   public void finish() {
      if (storage != null) {
         storage.finishedWriting();
      }
   }

   public void close() {
      if (storage != null) {
         storage.close();
      }
   }

   /** Convert a Micro-Manager Coords to the library's axes map (missing axes default to 0). */
   private static Map<String, Object> coordsToAxes(MmCoords coords) {
      Map<String, Object> axes = new LinkedHashMap<>();
      for (String axis : coords.getAxes()) {
         int idx = coords.getIndex(axis);
         axes.put(axis, Math.max(idx, 0));
      }
      return axes;
   }
}
