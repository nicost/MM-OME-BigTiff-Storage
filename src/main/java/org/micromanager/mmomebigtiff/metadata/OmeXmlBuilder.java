package org.micromanager.mmomebigtiff.metadata;

import org.micromanager.mmomebigtiff.Channel;
import org.micromanager.mmomebigtiff.PixelType;
import org.micromanager.mmomebigtiff.Version;

import java.util.List;

/**
 * Builds a minimal, valid OME-XML (schema {@value Version#OME_SCHEMA}) description of one
 * pyramidal image, to be embedded in the first IFD's {@code ImageDescription}.
 *
 * <p>Only the level-0 (full-resolution) IFDs are enumerated by {@code TiffData}; the pyramid
 * sub-resolutions live in TIFF {@code SubIFDs} and are discovered structurally by readers, which
 * is exactly how Bio-Formats/QuPath represent a pyramidal OME-TIFF.
 */
public final class OmeXmlBuilder {

   private OmeXmlBuilder() { }

   /** One full-resolution plane's mapping to its dimension coordinates and IFD index. */
   public static final class PlaneEntry {
      public final int ifd;
      public final int z;
      public final int c;
      public final int t;

      public PlaneEntry(int ifd, int z, int c, int t) {
         this.ifd = ifd;
         this.z = z;
         this.c = c;
         this.t = t;
      }
   }

   /**
    * @param imageName    Image name (e.g. the dataset/position name)
    * @param type         pixel type
    * @param significantBits nominal significant bits per sample (e.g. 12 for a 12-bit camera);
    *                     values &lt;= 0 fall back to the storage bit width
    * @param sizeX/Y      full-resolution width/height
    * @param sizeZ/C/T    dimension sizes (each >= 1)
    * @param physX/Y      physical pixel size along x/y
    * @param unit         physical unit (mapped to an OME UnitsLength value)
    * @param channels     optional per-channel descriptions (may be null/short; padded generically)
    * @param planes       one entry per written full-resolution plane
    */
   public static String build(String imageName, PixelType type, int significantBits,
                              int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
                              double physX, double physY, String unit,
                              List<Channel> channels, List<PlaneEntry> planes) {
      String omeUnit = omeUnit(unit);
      int sigBits = significantBits > 0 ? significantBits : type.bitDepth();
      StringBuilder sb = new StringBuilder(2048);
      sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      sb.append("<OME xmlns=\"").append(Version.OME_NS).append("\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            .append("xsi:schemaLocation=\"").append(Version.OME_NS).append(' ')
            .append(Version.OME_NS).append("/ome.xsd\">\n");
      sb.append("  <Image ID=\"Image:0\" Name=\"").append(esc(imageName)).append("\">\n");
      sb.append("    <Pixels ID=\"Pixels:0\" DimensionOrder=\"XYZCT\" Type=\"")
            .append(type.omeType()).append("\" Interleaved=\"false\" SignificantBits=\"")
            .append(sigBits).append("\" ")
            .append("SizeX=\"").append(sizeX).append("\" SizeY=\"").append(sizeY).append("\" ")
            .append("SizeZ=\"").append(sizeZ).append("\" SizeC=\"").append(sizeC).append("\" ")
            .append("SizeT=\"").append(sizeT).append("\"");
      if (physX > 0) {
         sb.append(" PhysicalSizeX=\"").append(physX).append("\" PhysicalSizeXUnit=\"")
               .append(omeUnit).append("\"");
      }
      if (physY > 0) {
         sb.append(" PhysicalSizeY=\"").append(physY).append("\" PhysicalSizeYUnit=\"")
               .append(omeUnit).append("\"");
      }
      sb.append(">\n");

      for (int c = 0; c < sizeC; c++) {
         Channel ch = channels != null && c < channels.size() ? channels.get(c) : null;
         sb.append("      <Channel ID=\"Channel:0:").append(c).append("\" SamplesPerPixel=\"1\"");
         if (ch != null) {
            sb.append(" Name=\"").append(esc(ch.getName())).append("\"");
            Integer color = argbColor(ch.getColor());
            if (color != null) {
               sb.append(" Color=\"").append(color.intValue()).append("\"");
            }
            if (ch.getEmissionWavelengthNm() != null) {
               sb.append(" EmissionWavelength=\"").append(ch.getEmissionWavelengthNm())
                     .append("\" EmissionWavelengthUnit=\"nm\"");
            }
            if (ch.getExcitationWavelengthNm() != null) {
               sb.append(" ExcitationWavelength=\"").append(ch.getExcitationWavelengthNm())
                     .append("\" ExcitationWavelengthUnit=\"nm\"");
            }
         }
         sb.append("/>\n");
      }

      for (PlaneEntry p : planes) {
         sb.append("      <TiffData FirstZ=\"").append(p.z).append("\" FirstC=\"").append(p.c)
               .append("\" FirstT=\"").append(p.t).append("\" IFD=\"").append(p.ifd)
               .append("\" PlaneCount=\"1\"/>\n");
      }

      sb.append("    </Pixels>\n");
      sb.append("  </Image>\n");
      sb.append("</OME>\n");
      return sb.toString();
   }

   /** Map a friendly unit name to an OME {@code UnitsLength} enumeration value. */
   private static String omeUnit(String unit) {
      if (unit == null) {
         return "µm";
      }
      switch (unit.toLowerCase()) {
         case "micrometer":
         case "micrometre":
         case "micron":
         case "um":
         case "µm":
            return "µm";
         case "nanometer":
         case "nanometre":
         case "nm":
            return "nm";
         case "millimeter":
         case "millimetre":
         case "mm":
            return "mm";
         default:
            return unit;
      }
   }

   /** Parse a "#RRGGBB" hex color to an OME signed RGBA int (alpha 255), or null. */
   static Integer argbColor(String hex) {
      if (hex == null) {
         return null;
      }
      String h = hex.startsWith("#") ? hex.substring(1) : hex;
      if (h.length() != 6) {
         return null;
      }
      try {
         int rgb = (int) Long.parseLong(h, 16);
         int r = (rgb >> 16) & 0xFF;
         int g = (rgb >> 8) & 0xFF;
         int b = rgb & 0xFF;
         long rgba = ((long) r << 24) | ((long) g << 16) | ((long) b << 8) | 0xFFL;
         return (int) rgba; // OME Color is a signed 32-bit RGBA
      } catch (NumberFormatException e) {
         return null;
      }
   }

   private static String esc(String s) {
      if (s == null) {
         return "";
      }
      StringBuilder b = new StringBuilder(s.length());
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         switch (c) {
            case '&': b.append("&amp;"); break;
            case '<': b.append("&lt;"); break;
            case '>': b.append("&gt;"); break;
            case '"': b.append("&quot;"); break;
            case '\'': b.append("&apos;"); break;
            default: b.append(c);
         }
      }
      return b.toString();
   }
}
