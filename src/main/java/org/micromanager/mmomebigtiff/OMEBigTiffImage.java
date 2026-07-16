package org.micromanager.mmomebigtiff;

/**
 * A pixel array plus its per-image metadata, returned by the read methods.
 *
 * <p>This is the dependency-neutral analogue of Micro-Manager's {@code TaggedImage}: pixels
 * are a Java primitive array ({@code byte[]}, {@code short[]} or {@code float[]}) and metadata
 * is an opaque JSON string. This keeps the library free of any Micro-Manager dependency while
 * remaining trivial to convert in an adapter.
 */
public final class OMEBigTiffImage {

   /**
    * Primitive pixel array: {@code byte[]}, {@code short[]} or {@code float[]}. For RGB images this
    * is a {@code byte[]} of {@code width*height*3} interleaved samples ordered R,G,B (the unused
    * alpha byte of Micro-Manager's 4-byte input is dropped on write).
    */
   public final Object pix;

   /** Per-image metadata as a JSON string, or {@code null} if none was stored. */
   public final String metadataJson;

   public OMEBigTiffImage(Object pix, String metadataJson) {
      this.pix = pix;
      this.metadataJson = metadataJson;
   }
}
