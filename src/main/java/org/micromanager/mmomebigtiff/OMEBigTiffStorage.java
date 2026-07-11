package org.micromanager.mmomebigtiff;

import org.micromanager.mmomebigtiff.metadata.OmeXmlBuilder;
import org.micromanager.mmomebigtiff.metadata.PerImageMetadataStore;
import org.micromanager.mmomebigtiff.tiff.PlaneLocation;
import org.micromanager.mmomebigtiff.tiff.TiffPyramidReader;
import org.micromanager.mmomebigtiff.tiff.TiffPyramidWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Disk-backed, streaming pyramidal OME-BigTIFF storage with concurrent read-while-write. Modeled
 * on {@code org.micromanager.mmomezarr.OMEZarrStorage} / {@code NDTiffStorage}: a single writer
 * thread drains a bounded queue (back-pressure), images are readable from an in-memory
 * write-pending buffer the instant they are queued, and every plane is written together with its
 * downsampled pyramid levels as TIFF {@code SubIFDs}.
 *
 * <p>The dataset is a folder containing one self-contained pyramidal OME-BigTIFF file per stage
 * position, an append-only per-image metadata sidecar ({@code ome-metadata.ndjson}), and a small
 * MM-specific descriptor ({@code mm-bigtiff.json}) used to reconstruct axis names/types and
 * summary/custom metadata on reopen. Each {@code .ome.tif} file is independently readable by any
 * OME-TIFF reader (QuPath, Bio-Formats, tifffile, ImageJ).
 *
 * <p><b>Pyramid note:</b> because a plane's SubIFD array is written inline with the plane, the
 * number of resolution levels is fixed once the first image is written; {@link
 * #setMaxResolutionLevel} therefore only raises the level count before writing begins.
 */
public final class OMEBigTiffStorage implements MultiresOMEBigTiffAPI {

   private static final String DESCRIPTOR_FILE = "mm-bigtiff.json";
   private static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

   private final Path root;
   private final String baseName;
   private final String uniqueAcqName;
   private final String summaryMetadataJson;
   private final OMEBigTiffStorageConfig config;
   private final PerImageMetadataStore perImageMeta;

   // Writer thread + bounded queue (NDTiff-style back-pressure).
   private final ExecutorService writeExecutor;
   private final LinkedBlockingQueue<FutureTask<Void>> writingQueue;
   private final int writingQueueMaxSize;
   private volatile Exception writingException;

   // Per-position TIFF writers/readers and plane index.
   private final Map<Integer, TiffPyramidWriter> writers = new ConcurrentHashMap<>();
   private final Map<Integer, TiffPyramidReader> readers = new ConcurrentHashMap<>();
   private final Map<Integer, Map<String, PlaneLocation>> planeIndex = new ConcurrentHashMap<>();
   private final Map<Integer, List<OmeXmlBuilder.PlaneEntry>> planeEntries = new ConcurrentHashMap<>();
   private final Map<Integer, int[]> zctSizeByPos = new ConcurrentHashMap<>(); // {sizeZ,sizeC,sizeT}

   // Read-during-write buffer (base image only; lower levels are synthesized on demand).
   private final Map<String, Pending> pending = new ConcurrentHashMap<>();
   private final Map<String, String> customMetadata = new ConcurrentHashMap<>();

   // Dense index for string-valued axis positions (e.g. channel "DAPI"), per axis, in order of
   // first appearance. Needed because OME-TIFF addresses planes by integer Z/C/T coordinates.
   // Persisted in the descriptor so a reload reproduces the same mapping.
   private final Map<String, Map<String, Integer>> axisValueIndex = new ConcurrentHashMap<>();

   // Lazily resolved layout (set on first image / on load).
   private volatile boolean layoutResolved;
   private volatile PixelType pixelType;
   private volatile int bitDepth; // nominal significant bits (e.g. 12 for a 12-bit camera)
   private volatile int fullWidth;
   private volatile int fullHeight;
   private volatile List<AxisInfo> nonSpatialAxes; // ordered array axes (excl. y/x, position)
   private volatile boolean multiPosition;
   private volatile int numResLevels;

   private volatile boolean finished;
   private final boolean readOnly;

   private static final class Pending {
      final Object pix;
      final int width;
      final int height;

      Pending(Object pix, int width, int height) {
         this.pix = pix;
         this.width = width;
         this.height = height;
      }
   }

   // -------------------------------------------------------------------------
   // Construction
   // -------------------------------------------------------------------------

   /**
    * Create a new dataset for writing.
    *
    * @param dir                 parent directory
    * @param name                dataset name (a {@code .ome.tiff} folder is created under dir)
    * @param summaryMetadataJson opaque summary metadata JSON (may be null)
    * @param config              storage configuration (may be null for defaults)
    */
   public OMEBigTiffStorage(String dir, String name, String summaryMetadataJson,
                            OMEBigTiffStorageConfig config) {
      this.readOnly = false;
      this.config = config == null ? new OMEBigTiffStorageConfig() : config;
      this.summaryMetadataJson = summaryMetadataJson;
      this.numResLevels = this.config.getNumResolutionLevels();

      String cleanName = name.endsWith(".ome.tiff") ? name.substring(0, name.length() - 9)
            : (name.endsWith(".ome.tif") ? name.substring(0, name.length() - 8) : name);
      Path candidate = uniquify(Paths.get(dir, cleanName + ".ome.tiff"));
      this.root = candidate;
      this.baseName = candidate.getFileName().toString().replace(".ome.tiff", "");
      this.uniqueAcqName = root.getFileName().toString();
      try {
         Files.createDirectories(root);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }

      this.perImageMeta = PerImageMetadataStore.createWritable(root);
      this.writingQueueMaxSize = this.config.getSavingQueueSize();
      this.writingQueue = new LinkedBlockingQueue<>(writingQueueMaxSize);
      this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
         Thread t = new Thread(r, "OMEBigTiff writing executor");
         t.setDaemon(true);
         return t;
      });
   }

   /** Open an existing dataset for reading. */
   public static OMEBigTiffStorage load(String dir) {
      return load(dir, null);
   }

   /** Open an existing dataset for reading with a custom config. */
   public static OMEBigTiffStorage load(String dir, OMEBigTiffStorageConfig config) {
      return new OMEBigTiffStorage(Paths.get(dir),
            config == null ? new OMEBigTiffStorageConfig() : config);
   }

   private OMEBigTiffStorage(Path root, OMEBigTiffStorageConfig config) {
      this.readOnly = true;
      this.finished = true;
      this.root = root;
      this.baseName = root.getFileName().toString().replace(".ome.tiff", "");
      this.uniqueAcqName = root.getFileName().toString();
      this.config = config;
      this.perImageMeta = PerImageMetadataStore.openReadOnly(root);
      this.writingQueueMaxSize = this.config.getSavingQueueSize();
      this.writingQueue = new LinkedBlockingQueue<>(writingQueueMaxSize);
      this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
         Thread t = new Thread(r, "OMEBigTiff writing executor");
         t.setDaemon(true);
         return t;
      });
      String summary;
      try {
         summary = loadFromDisk();
      } catch (Exception e) {
         throw new RuntimeException("Failed to load dataset at " + root, e);
      }
      this.summaryMetadataJson = summary;
   }

   // -------------------------------------------------------------------------
   // Write path
   // -------------------------------------------------------------------------

   @Override
   public Future<Void> putImage(Object pixels, String metadataJson, Map<String, Object> axes,
                                boolean rgb, int bitDepth, int imageHeight, int imageWidth) {
      if (readOnly || finished) {
         throw new IllegalStateException("Dataset is not writable.");
      }
      rethrowIfWriteFailed();
      if (!layoutResolved) {
         synchronized (this) {
            if (!layoutResolved) {
               resolveLayout(axes, rgb, bitDepth, imageWidth, imageHeight);
            }
         }
      }
      final Map<String, Object> axesCopy = new LinkedHashMap<>(axes);
      final String axesKey = AxesKey.serialize(axesCopy);
      final int posIndex = posIndexOf(axesCopy);

      // Record per-image metadata in memory synchronously (readable immediately); the flushed
      // sidecar append happens on the writer thread.
      perImageMeta.put(axesKey, metadataJson);
      pending.put(axesKey, new Pending(pixels, imageWidth, imageHeight));

      return submitWrite(() -> {
         TiffPyramidWriter writer = writerFor(posIndex);
         PlaneLocation loc = writer.writePlane(pixels, imageWidth, imageHeight, metadataJson);
         planeIndex.computeIfAbsent(posIndex, k -> new ConcurrentHashMap<>()).put(axesKey, loc);
         recordPlaneMapping(posIndex, axesCopy);
         perImageMeta.append(axesCopy, metadataJson);
         pending.remove(axesKey);
         return null;
      });
   }

   private TiffPyramidWriter writerFor(int posIndex) throws IOException {
      TiffPyramidWriter w = writers.get(posIndex);
      if (w != null) {
         return w;
      }
      Path file = root.resolve(fileNameForPosition(posIndex));
      w = new TiffPyramidWriter(file, pixelType, numResLevels, config.getCompression(),
            BYTE_ORDER, config.getPixelSizeX());
      writers.put(posIndex, w);
      planeEntries.put(posIndex, new ArrayList<>());
      zctSizeByPos.put(posIndex, new int[]{1, 1, 1});
      return w;
   }

   /** Record a plane's (z,c,t) → IFD-index mapping and update the position's Z/C/T sizes. */
   private void recordPlaneMapping(int posIndex, Map<String, Object> axes) {
      int[] zct = zctOf(axes);
      List<OmeXmlBuilder.PlaneEntry> entries = planeEntries.get(posIndex);
      int ifd = entries.size();
      entries.add(new OmeXmlBuilder.PlaneEntry(ifd, zct[0], zct[1], zct[2]));
      int[] sizes = zctSizeByPos.get(posIndex);
      sizes[0] = Math.max(sizes[0], zct[0] + 1);
      sizes[1] = Math.max(sizes[1], zct[1] + 1);
      sizes[2] = Math.max(sizes[2], zct[2] + 1);
   }

   // -------------------------------------------------------------------------
   // Pyramid
   // -------------------------------------------------------------------------

   @Override
   public int getNumResLevels() {
      return numResLevels;
   }

   @Override
   public synchronized void setMaxResolutionLevel(int newNumLevels) {
      if (newNumLevels <= numResLevels) {
         return;
      }
      if (layoutResolved || !writers.isEmpty()) {
         throw new IllegalStateException(
               "OME-BigTIFF pyramid depth is fixed once writing begins; set "
                     + "numResolutionLevels in the config before the first putImage.");
      }
      this.numResLevels = newNumLevels;
      this.config.numResolutionLevels(newNumLevels);
   }

   // -------------------------------------------------------------------------
   // Read path
   // -------------------------------------------------------------------------

   @Override
   public OMEBigTiffImage getImage(Map<String, Object> axes) {
      return getImage(axes, 0);
   }

   @Override
   public OMEBigTiffImage getImage(Map<String, Object> axes, int level) {
      String axesKey = AxesKey.serialize(axes);
      Object pix = readPixels(axes, axesKey, level);
      if (pix == null) {
         return null;
      }
      return new OMEBigTiffImage(pix, perImageMeta.get(axesKey));
   }

   private Object readPixels(Map<String, Object> axes, String axesKey, int level) {
      if (level < 0 || level >= numResLevels) {
         return null;
      }
      Pending p = pending.get(axesKey);
      if (p != null) {
         Object pix = p.pix;
         int w = p.width;
         int h = p.height;
         for (int l = 0; l < level; l++) {
            pix = Downsampler.downsample(pix, w, h, pixelType);
            w = Downsampler.downWidth(w);
            h = Downsampler.downHeight(h);
         }
         return pix;
      }
      int posIndex = posIndexOf(axes);
      Map<String, PlaneLocation> byKey = planeIndex.get(posIndex);
      PlaneLocation loc = byKey == null ? null : byKey.get(axesKey);
      if (loc == null || level >= loc.numLevels()) {
         return null;
      }
      try {
         TiffPyramidWriter w = writers.get(posIndex);
         if (w != null) {
            return w.readLevel(loc, level);
         }
         TiffPyramidReader r = readers.get(posIndex);
         if (r != null) {
            return r.readLevel(loc, level, config.getCompression() == Compression.DEFLATE);
         }
         return null;
      } catch (IOException e) {
         throw new UncheckedIOException("Failed to read plane at level " + level, e);
      }
   }

   @Override
   public String getImageMetadata(Map<String, Object> axes) {
      return perImageMeta.get(AxesKey.serialize(axes));
   }

   @Override
   public EssentialImageMetadata getEssentialImageMetadata(Map<String, Object> axes) {
      return getEssentialImageMetadata(axes, 0);
   }

   @Override
   public EssentialImageMetadata getEssentialImageMetadata(Map<String, Object> axes, int level) {
      String axesKey = AxesKey.serialize(axes);
      if (!hasImage(axes, level)) {
         return null;
      }
      Pending p = pending.get(axesKey);
      int w;
      int h;
      if (p != null) {
         w = p.width;
         h = p.height;
         for (int l = 0; l < level; l++) {
            w = Downsampler.downWidth(w);
            h = Downsampler.downHeight(h);
         }
      } else {
         Map<String, PlaneLocation> byKey = planeIndex.get(posIndexOf(axes));
         PlaneLocation loc = byKey == null ? null : byKey.get(axesKey);
         if (loc == null || level >= loc.numLevels()) {
            return null;
         }
         w = loc.width[level];
         h = loc.height[level];
      }
      return new EssentialImageMetadata(w, h, bitDepth, false);
   }

   @Override
   public boolean hasImage(Map<String, Object> axes) {
      return hasImage(axes, 0);
   }

   @Override
   public boolean hasImage(Map<String, Object> axes, int level) {
      if (level < 0 || level >= numResLevels) {
         return false;
      }
      String axesKey = AxesKey.serialize(axes);
      if (pending.containsKey(axesKey)) {
         return true;
      }
      Map<String, PlaneLocation> byKey = planeIndex.get(posIndexOf(axes));
      return byKey != null && byKey.containsKey(axesKey);
   }

   @Override
   public Set<Map<String, Object>> getAxesSet() {
      Set<Map<String, Object>> out = new HashSet<>();
      for (String key : perImageMeta.keySet()) {
         out.add(AxesKey.deserialize(key));
      }
      return out;
   }

   // -------------------------------------------------------------------------
   // Metadata accessors
   // -------------------------------------------------------------------------

   @Override
   public String getSummaryMetadata() {
      return summaryMetadataJson;
   }

   @Override
   public synchronized void setCustomMetadata(String key, String json) {
      customMetadata.put(key, json);
      if (!readOnly && layoutResolved) {
         writeDescriptor();
      }
   }

   @Override
   public String getCustomMetadata(String key) {
      return customMetadata.get(key);
   }

   // -------------------------------------------------------------------------
   // Lifecycle
   // -------------------------------------------------------------------------

   @Override
   public boolean isFinished() {
      return finished;
   }

   @Override
   public void checkForWritingException() throws Exception {
      if (writingException != null) {
         throw writingException;
      }
   }

   @Override
   public void finishedWriting() {
      if (readOnly || finished) {
         return;
      }
      Future<Void> f = submitWrite(() -> {
         for (Map.Entry<Integer, TiffPyramidWriter> e : writers.entrySet()) {
            int pos = e.getKey();
            byte[] omeXml = buildOmeXml(pos).getBytes(StandardCharsets.UTF_8);
            e.getValue().finish(omeXml);
         }
         writeDescriptor();
         return null;
      });
      awaitWrite(f);
      finished = true;
   }

   @Override
   public String getDiskLocation() {
      return root.toString();
   }

   @Override
   public String getUniqueAcqName() {
      return uniqueAcqName;
   }

   @Override
   public int getWritingQueueTaskSize() {
      return writingQueue == null ? 0 : writingQueue.size();
   }

   @Override
   public int getWritingQueueTaskMaxSize() {
      return writingQueueMaxSize;
   }

   @Override
   public void close() {
      try {
         closeAndWait();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   @Override
   public void closeAndWait() throws InterruptedException {
      if (!readOnly && !finished) {
         finishedWriting();
      }
      if (writeExecutor != null) {
         writeExecutor.shutdown();
      }
      perImageMeta.close();
      for (TiffPyramidWriter w : writers.values()) {
         w.close();
      }
      for (TiffPyramidReader r : readers.values()) {
         r.close();
      }
   }

   // -------------------------------------------------------------------------
   // Bounds
   // -------------------------------------------------------------------------

   @Override
   public int[] getImageBounds() {
      return new int[]{0, 0, fullWidth, fullHeight};
   }

   // -------------------------------------------------------------------------
   // Writer queue plumbing (NDTiff-style blocking handoff)
   // -------------------------------------------------------------------------

   private Future<Void> submitWrite(java.util.concurrent.Callable<Void> task) {
      FutureTask<Void> ft = new FutureTask<>(() -> {
         try {
            return task.call();
         } catch (Exception e) {
            writingException = e;
            throw e;
         }
      });
      try {
         writingQueue.put(ft); // blocks when full => back-pressure
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new RuntimeException("Interrupted while queueing write", e);
      }
      writeExecutor.submit(() -> {
         try {
            writingQueue.take().run();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      });
      return ft;
   }

   private void awaitWrite(Future<Void> f) {
      try {
         f.get();
      } catch (InterruptedException e) {
         // Must not return normally: the caller would mark the dataset finished and close the
         // file channels while the writer thread may still be finalizing them.
         Thread.currentThread().interrupt();
         throw new RuntimeException("Interrupted while waiting for pending writes", e);
      } catch (ExecutionException e) {
         throw new RuntimeException(e.getCause());
      }
   }

   private void rethrowIfWriteFailed() {
      if (writingException != null) {
         throw new RuntimeException("A previous write failed", writingException);
      }
   }

   // -------------------------------------------------------------------------
   // Layout resolution
   // -------------------------------------------------------------------------

   private void resolveLayout(Map<String, Object> axes, boolean rgb, int bitDepth,
                              int width, int height) {
      this.pixelType = PixelType.of(rgb, bitDepth);
      this.bitDepth = bitDepth > 0 ? bitDepth : this.pixelType.bitDepth();
      this.fullWidth = width;
      this.fullHeight = height;
      this.multiPosition = axes.containsKey(config.getPositionAxis());
      this.nonSpatialAxes = resolveAxes(axes);
      validateDimensions(nonSpatialAxes);
      writeDescriptor();
      this.layoutResolved = true;
   }

   private List<AxisInfo> resolveAxes(Map<String, Object> firstAxes) {
      if (!config.getAxes().isEmpty()) {
         List<AxisInfo> out = new ArrayList<>();
         for (AxisInfo a : config.getAxes()) {
            if (a.getType() != DimensionType.POSITION
                  && !a.getName().equals(config.getPositionAxis())) {
               out.add(a);
            }
         }
         return out;
      }
      List<String> names = new ArrayList<>(firstAxes.keySet());
      names.remove(config.getPositionAxis());
      names.sort(Comparator.comparingInt(OMEBigTiffStorage::canonicalRank)
            .thenComparing(Comparator.naturalOrder()));
      List<AxisInfo> out = new ArrayList<>();
      for (String n : names) {
         out.add(AxisInfo.builder(n).type(inferType(n)).build());
      }
      return out;
   }

   /** OME-TIFF only supports Z/C/T; reject anything else and any duplicate dimension. */
   private void validateDimensions(List<AxisInfo> axes) {
      boolean z = false;
      boolean c = false;
      boolean t = false;
      for (AxisInfo a : axes) {
         String dim = a.getType().omeDimension();
         if (dim == null) {
            throw new IllegalArgumentException("Axis '" + a.getName() + "' has type "
                  + a.getType() + ", which has no OME-TIFF dimension. OME-BigTIFF supports only "
                  + "time (T), channel (C) and z/space (Z) axes.");
         }
         if ("Z".equals(dim)) {
            if (z) {
               throw new IllegalArgumentException("More than one Z/space axis is not supported.");
            }
            z = true;
         } else if ("C".equals(dim)) {
            if (c) {
               throw new IllegalArgumentException("More than one channel axis is not supported.");
            }
            c = true;
         } else {
            if (t) {
               throw new IllegalArgumentException("More than one time axis is not supported.");
            }
            t = true;
         }
      }
   }

   private static int canonicalRank(String axis) {
      switch (axis.toLowerCase()) {
         case "time": case "t": return 0;
         case "channel": case "c": return 1;
         case "z": return 2;
         default: return 3;
      }
   }

   private static DimensionType inferType(String axis) {
      switch (axis.toLowerCase()) {
         case "time": case "t": return DimensionType.TIME;
         case "channel": case "c": return DimensionType.CHANNEL;
         case "z": return DimensionType.SPACE;
         default: return DimensionType.OTHER;
      }
   }

   /**
    * Integer position of an axis value: numbers directly, strings via the per-axis
    * first-appearance index (OME-TIFF addresses planes by integer coordinates).
    */
   private int axisIndexOf(Map<String, Object> axes, String axis, int dflt) {
      Object v = axes.get(axis);
      if (v instanceof Number) {
         return ((Number) v).intValue();
      }
      if (v instanceof String) {
         return valueIndex(axis, (String) v);
      }
      return dflt;
   }

   /** Dense index of a string axis value, assigned in order of first appearance. */
   private int valueIndex(String axis, String value) {
      Map<String, Integer> byValue =
            axisValueIndex.computeIfAbsent(axis, k -> new LinkedHashMap<>());
      synchronized (byValue) {
         Integer i = byValue.get(value);
         if (i == null) {
            i = byValue.size();
            byValue.put(value, i);
         }
         return i;
      }
   }

   /** Map an axes map to its (z,c,t) coordinate using the resolved axis types. */
   private int[] zctOf(Map<String, Object> axes) {
      int z = 0;
      int c = 0;
      int t = 0;
      for (AxisInfo a : nonSpatialAxes) {
         int v = axisIndexOf(axes, a.getName(), 0);
         String dim = a.getType().omeDimension();
         if ("Z".equals(dim)) {
            z = v;
         } else if ("C".equals(dim)) {
            c = v;
         } else if ("T".equals(dim)) {
            t = v;
         }
      }
      return new int[]{z, c, t};
   }

   private AxisInfo channelAxis() {
      for (AxisInfo a : nonSpatialAxes) {
         if (a.isChannel()) {
            return a;
         }
      }
      return null;
   }

   // -------------------------------------------------------------------------
   // OME-XML + descriptor
   // -------------------------------------------------------------------------

   private String buildOmeXml(int posIndex) {
      int[] sizes = zctSizeByPos.getOrDefault(posIndex, new int[]{1, 1, 1});
      int sizeZ = dimCount(DimensionType.SPACE, sizes[0]);
      int sizeC = dimCount(DimensionType.CHANNEL, sizes[1]);
      int sizeT = dimCount(DimensionType.TIME, sizes[2]);
      AxisInfo ch = channelAxis();
      List<Channel> channels = ch == null ? null : ch.getChannels();
      if (channels == null && ch != null) {
         channels = channelsFromStringValues(ch.getName());
      }
      if (channels != null) {
         sizeC = Math.max(sizeC, channels.size());
      }
      String imageName = multiPosition ? baseName + "_p" + posIndex : baseName;
      List<OmeXmlBuilder.PlaneEntry> entries =
            planeEntries.getOrDefault(posIndex, new ArrayList<>());
      return OmeXmlBuilder.build(imageName, pixelType, bitDepth, fullWidth, fullHeight,
            sizeZ, sizeC, sizeT, config.getPixelSizeX(), config.getPixelSizeY(),
            config.getSpatialUnit(), channels, entries);
   }

   /** Synthesize Channel names from string-valued channel positions, if any were used. */
   private List<Channel> channelsFromStringValues(String axisName) {
      Map<String, Integer> byValue = axisValueIndex.get(axisName);
      if (byValue == null) {
         return null;
      }
      synchronized (byValue) {
         List<Channel> out = new ArrayList<>(byValue.size());
         for (String name : byValue.keySet()) { // LinkedHashMap: iteration order == index order
            out.add(new Channel(name));
         }
         return out;
      }
   }

   /** Declared fixed count for a dimension if any axis of that type declares one, else observed. */
   private int dimCount(DimensionType type, int observed) {
      for (AxisInfo a : nonSpatialAxes) {
         if (a.getType() == type && a.getCount() != null) {
            return Math.max(a.getCount(), observed);
         }
      }
      return observed;
   }

   private synchronized void writeDescriptor() {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("formatMajor", Version.MAJOR);
      d.put("formatMinor", Version.MINOR);
      d.put("positionAxis", config.getPositionAxis());
      d.put("multiPosition", multiPosition);
      d.put("byteOrder", "LITTLE_ENDIAN");
      d.put("compression", config.getCompression().getId());
      d.put("numResolutionLevels", numResLevels);
      d.put("pixelType", pixelType == null ? null : pixelType.omeType());
      d.put("bitDepth", pixelType == null ? null : bitDepth);
      d.put("fullWidth", fullWidth);
      d.put("fullHeight", fullHeight);
      d.put("pixelSizeY", config.getPixelSizeY());
      d.put("pixelSizeX", config.getPixelSizeX());
      d.put("spatialUnit", config.getSpatialUnit());
      List<Map<String, Object>> axesList = new ArrayList<>();
      if (nonSpatialAxes != null) {
         for (AxisInfo a : nonSpatialAxes) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", a.getName());
            am.put("type", a.getType().name());
            if (a.getUnit() != null) {
               am.put("unit", a.getUnit());
            }
            if (a.getCount() != null) {
               am.put("count", a.getCount());
            }
            axesList.add(am);
         }
      }
      d.put("axes", axesList);
      if (!axisValueIndex.isEmpty()) {
         Map<String, Object> values = new LinkedHashMap<>();
         for (Map.Entry<String, Map<String, Integer>> e : axisValueIndex.entrySet()) {
            synchronized (e.getValue()) {
               values.put(e.getKey(), new ArrayList<>(e.getValue().keySet()));
            }
         }
         d.put("axisValues", values);
      }
      if (summaryMetadataJson != null) {
         d.put("summary", JsonUtil.parseObject(summaryMetadataJson));
      }
      if (!customMetadata.isEmpty()) {
         Map<String, Object> custom = new LinkedHashMap<>();
         for (Map.Entry<String, String> e : customMetadata.entrySet()) {
            custom.put(e.getKey(), JsonUtil.parseObject(e.getValue()));
         }
         d.put("custom", custom);
      }
      try {
         Path tmp = root.resolve(DESCRIPTOR_FILE + ".tmp");
         Files.write(tmp, JsonUtil.toJson(d).getBytes(StandardCharsets.UTF_8));
         Files.move(tmp, root.resolve(DESCRIPTOR_FILE),
               java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }

   // -------------------------------------------------------------------------
   // Load path
   // -------------------------------------------------------------------------

   @SuppressWarnings("unchecked")
   private String loadFromDisk() throws Exception {
      Path descriptor = root.resolve(DESCRIPTOR_FILE);
      if (!Files.exists(descriptor)) {
         throw new IOException("Not an MM-OME-BigTiff dataset (missing " + DESCRIPTOR_FILE
               + "): " + root);
      }
      String json = new String(Files.readAllBytes(descriptor), StandardCharsets.UTF_8);
      Map<String, Object> d = JsonUtil.parseObject(json);

      this.multiPosition = Boolean.TRUE.equals(d.get("multiPosition"));
      this.numResLevels = ((Number) d.getOrDefault("numResolutionLevels", 1)).intValue();
      this.fullWidth = ((Number) d.getOrDefault("fullWidth", 0)).intValue();
      this.fullHeight = ((Number) d.getOrDefault("fullHeight", 0)).intValue();
      Object pt = d.get("pixelType");
      this.pixelType = pt == null ? PixelType.GRAY16 : PixelType.fromOmeType(String.valueOf(pt));
      Object bd = d.get("bitDepth");
      this.bitDepth = bd instanceof Number && ((Number) bd).intValue() > 0
            ? ((Number) bd).intValue() : pixelType.bitDepth();
      if (d.get("positionAxis") != null) {
         config.positionAxis(String.valueOf(d.get("positionAxis")));
      }
      if (d.get("compression") != null) {
         config.compression(Compression.fromId(String.valueOf(d.get("compression"))));
      }
      if (d.get("pixelSizeY") != null && d.get("pixelSizeX") != null) {
         config.pixelSize(((Number) d.get("pixelSizeY")).doubleValue(),
               ((Number) d.get("pixelSizeX")).doubleValue());
      }
      if (d.get("spatialUnit") != null) {
         config.spatialUnit(String.valueOf(d.get("spatialUnit")));
      }

      List<AxisInfo> axes = new ArrayList<>();
      Object axesObj = d.get("axes");
      if (axesObj instanceof List) {
         for (Object o : (List<Object>) axesObj) {
            Map<String, Object> am = (Map<String, Object>) o;
            AxisInfo.Builder b = AxisInfo.builder(String.valueOf(am.get("name")))
                  .type(DimensionType.valueOf(String.valueOf(am.get("type"))));
            if (am.get("unit") != null) {
               b.unit(String.valueOf(am.get("unit")));
            }
            if (am.get("count") != null) {
               b.count(((Number) am.get("count")).intValue());
            }
            axes.add(b.build());
         }
      }
      this.nonSpatialAxes = axes;

      // Restore string-value -> index mappings so zctOf/posIndexOf resolve as when written.
      if (d.get("axisValues") instanceof Map) {
         for (Map.Entry<String, Object> e : ((Map<String, Object>) d.get("axisValues")).entrySet()) {
            Map<String, Integer> byValue = new LinkedHashMap<>();
            int i = 0;
            for (Object v : (List<Object>) e.getValue()) {
               byValue.put(String.valueOf(v), i++);
            }
            axisValueIndex.put(e.getKey(), byValue);
         }
      }

      String summary = null;
      if (d.get("summary") != null) {
         summary = JsonUtil.toJson(d.get("summary"));
      }
      if (d.get("custom") instanceof Map) {
         for (Map.Entry<String, Object> e : ((Map<String, Object>) d.get("custom")).entrySet()) {
            customMetadata.put(e.getKey(), JsonUtil.toJson(e.getValue()));
         }
      }

      // Open each position file and build the plane index from the sidecar's axes maps.
      for (Map<String, Object> axesMap : getAxesSetInternal()) {
         int posIndex = posIndexOf(axesMap);
         TiffPyramidReader r = readerFor(posIndex);
         if (r == null) {
            continue;
         }
         int[] zct = zctOf(axesMap);
         PlaneLocation loc = r.location(zct[0], zct[1], zct[2]);
         if (loc != null) {
            planeIndex.computeIfAbsent(posIndex, k -> new ConcurrentHashMap<>())
                  .put(AxesKey.serialize(axesMap), loc);
         }
      }

      this.layoutResolved = true;
      return summary;
   }

   private Set<Map<String, Object>> getAxesSetInternal() {
      Set<Map<String, Object>> out = new HashSet<>();
      for (String key : perImageMeta.keySet()) {
         out.add(AxesKey.deserialize(key));
      }
      return out;
   }

   private TiffPyramidReader readerFor(int posIndex) {
      TiffPyramidReader r = readers.get(posIndex);
      if (r != null) {
         return r;
      }
      Path file = root.resolve(fileNameForPosition(posIndex));
      if (!Files.exists(file)) {
         return null;
      }
      try {
         r = TiffPyramidReader.open(file);
         readers.put(posIndex, r);
         return r;
      } catch (IOException e) {
         throw new UncheckedIOException("Failed to open " + file, e);
      }
   }

   // -------------------------------------------------------------------------
   // Small helpers
   // -------------------------------------------------------------------------

   private int posIndexOf(Map<String, Object> axes) {
      return multiPosition ? axisIndexOf(axes, config.getPositionAxis(), 0) : 0;
   }

   private String fileNameForPosition(int posIndex) {
      return multiPosition ? baseName + "_p" + posIndex + ".ome.tif" : baseName + ".ome.tif";
   }

   private static Path uniquify(Path candidate) {
      if (!Files.exists(candidate)) {
         return candidate;
      }
      String base = candidate.getFileName().toString().replace(".ome.tiff", "");
      Path parent = candidate.getParent();
      for (int i = 1; ; i++) {
         Path p = parent.resolve(base + "_" + i + ".ome.tiff");
         if (!Files.exists(p)) {
            return p;
         }
      }
   }
}
