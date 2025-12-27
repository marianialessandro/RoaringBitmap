import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A small, dependency-free benchmark harness intended for A/B comparisons
 * between different RoaringBitmap implementations (different repos/builds).
 *
 * This is NOT a replacement for JMH, but it avoids the most common pitfalls:
 * - explicit warmup phase (not measured)
 * - multiple measurement samples with robust statistics (median, p90, mean, stdev)
 * - no I/O used to prevent dead-code elimination (uses a volatile sink)
 * - dataset loaded once
 * - adaptive batching to reduce timer noise for very fast operations
 *
 * Usage:
 *   java simplebenchmark <dataset.zip> [--warmup N] [--samples N] [--targetMs M]
 *
 * Output:
 *   Two lines per dataset: one for "roaring" and one for "buffer"
 *   with median ns/op (and additional stats in parentheses).
 */
public class simplebenchmark {

  // Prevent dead-code elimination without introducing I/O in hot paths
  private static volatile long SINK = 0;

  // Default config (tunable via CLI)
  private static final int DEFAULT_WARMUP_ITERS = 10;
  private static final int DEFAULT_SAMPLES = 20;
  private static final int MAX_BATCH = 100;
  private static final long DEFAULT_TARGET_SAMPLE_NS = 50_000_000L; // 50ms

  private static final DecimalFormat DF_BITS_PER_VAL = new DecimalFormat("0.00");

  @FunctionalInterface
  private interface Task {
    long run();
  }

  private static final class Config {
    final int warmupIters;
    final int samples;
    final long targetSampleNs;

    Config(int warmupIters, int samples, long targetSampleNs) {
      this.warmupIters = warmupIters;
      this.samples = samples;
      this.targetSampleNs = targetSampleNs;
    }
  }

  private static final class Stats {
    final int batch;
    final long[] nsPerOpSamplesSorted;
    final double mean;
    final double stdev;
    final long median;
    final long p90;
    final long min;
    final long max;

    Stats(int batch, long[] sorted, double mean, double stdev, long median, long p90, long min, long max) {
      this.batch = batch;
      this.nsPerOpSamplesSorted = sorted;
      this.mean = mean;
      this.stdev = stdev;
      this.median = median;
      this.p90 = p90;
      this.min = min;
      this.max = max;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      printUsageAndExit();
    }

    String datasetPath = args[0];
    Config cfg = parseConfig(args);

    try {
      benchmark(new ZipRealDataRetriever(datasetPath), cfg);
    } catch (FileNotFoundException fnf) {
      System.err.println("I can't find the file: " + datasetPath);
    }
  }

  private static void printUsageAndExit() {
    System.out.println("Usage:");
    System.out.println("  java simplebenchmark <dataset.zip> [--warmup N] [--samples N] [--targetMs M]");
    System.out.println();
    System.out.println("Example:");
    System.out.println("  java simplebenchmark ../real-roaring-dataset/.../census1881.zip --warmup 15 --samples 30 --targetMs 50");
    System.exit(1);
  }

  private static Config parseConfig(String[] args) {
    int warmup = DEFAULT_WARMUP_ITERS;
    int samples = DEFAULT_SAMPLES;
    long targetNs = DEFAULT_TARGET_SAMPLE_NS;

    for (int i = 1; i < args.length; i++) {
      String a = args[i];
      if ("--warmup".equals(a) && i + 1 < args.length) {
        warmup = Integer.parseInt(args[++i]);
      } else if ("--samples".equals(a) && i + 1 < args.length) {
        samples = Integer.parseInt(args[++i]);
      } else if ("--targetMs".equals(a) && i + 1 < args.length) {
        long ms = Long.parseLong(args[++i]);
        targetNs = Math.max(1L, ms) * 1_000_000L;
      } else {
        System.err.println("Unknown/invalid argument: " + a);
        printUsageAndExit();
      }
    }

    if (warmup < 0) warmup = 0;
    if (samples < 5) samples = 5;

    return new Config(warmup, samples, targetNs);
  }

  private static void benchmark(ZipRealDataRetriever zip, Config cfg) throws IOException {
    // Load once
    List<int[]> positions = zip.fetchBitPositions();
    if (positions.isEmpty()) {
      System.err.println("Dataset appears empty: " + zip.getName());
      return;
    }

    int maxvalue = universeSizeFromPositions(positions);

    List<RoaringBitmap> bitmaps = toBitmaps(positions);
    List<ImmutableRoaringBitmap> bufferBitmaps = toBufferBitmaps(positions);

    // Precompute bits/value (informational)
    double roaringBitsPerVal = bitsPerValue(bitmaps);
    double bufferBitsPerVal = bitsPerValueBuffer(bufferBitmaps);

    // Print a stable, parse-friendly header
    System.out.println(
        "dataset\timpl\tbitsPerValue\tand2by2(ns/op)\tor2by2(ns/op)\twideOr(ns/op)\tcontains3(ns/op)");

    // Measure roaring
    ResultLine roaring = runRoaringSuite(zip.getName(), roaringBitsPerVal, bitmaps, maxvalue, cfg);
    System.out.println(roaring.asTsv());

    // Measure buffer
    ResultLine buffer = runBufferSuite(zip.getName(), bufferBitsPerVal, bufferBitmaps, maxvalue, cfg);
    System.out.println(buffer.asTsv());
  }

  private static int universeSizeFromPositions(List<int[]> positions) {
    int max = 0;
    for (int[] arr : positions) {
      if (arr != null && arr.length > 0) {
        int last = arr[arr.length - 1]; // datasets are sorted
        if (last > max) max = last;
      }
    }
    return max;
  }

  private static List<RoaringBitmap> toBitmaps(List<int[]> positions) {
    ArrayList<RoaringBitmap> answer = new ArrayList<>(positions.size());
    for (int[] data : positions) {
      RoaringBitmap r = RoaringBitmap.bitmapOf(data);
      r.runOptimize();
      answer.add(r);
    }
    return answer;
  }

  private static List<ImmutableRoaringBitmap> toBufferBitmaps(List<int[]> positions) {
    ArrayList<ImmutableRoaringBitmap> answer = new ArrayList<>(positions.size());
    for (int[] data : positions) {
      MutableRoaringBitmap r = MutableRoaringBitmap.bitmapOf(data);
      r.runOptimize();
      answer.add(r);
    }
    return answer;
  }

  private static double bitsPerValue(List<RoaringBitmap> bitmaps) {
    long totalCard = 0;
    long totalBytes = 0;
    for (RoaringBitmap r : bitmaps) {
      totalCard += r.getCardinality();
      totalBytes += r.getSizeInBytes();
    }
    if (totalCard == 0) return Double.NaN;
    return (totalBytes * 8.0) / totalCard;
  }

  private static double bitsPerValueBuffer(List<ImmutableRoaringBitmap> bitmaps) {
    long totalCard = 0;
    long totalBytes = 0;
    for (ImmutableRoaringBitmap r : bitmaps) {
      totalCard += r.getCardinality();
      totalBytes += r.getSizeInBytes();
    }
    if (totalCard == 0) return Double.NaN;
    return (totalBytes * 8.0) / totalCard;
  }

  private static final class ResultLine {
    final String dataset;
    final String impl;
    final double bitsPerValue;
    final Stats and2by2;
    final Stats or2by2;
    final Stats wideOr;
    final Stats contains3;

    ResultLine(String dataset, String impl, double bitsPerValue, Stats and2by2, Stats or2by2, Stats wideOr, Stats contains3) {
      this.dataset = dataset;
      this.impl = impl;
      this.bitsPerValue = bitsPerValue;
      this.and2by2 = and2by2;
      this.or2by2 = or2by2;
      this.wideOr = wideOr;
      this.contains3 = contains3;
    }

    String asTsv() {
      // keep main columns compact (median), while still leaving stats in parentheses for humans
      return dataset + "\t"
          + impl + "\t"
          + String.format("%1$-10s", DF_BITS_PER_VAL.format(bitsPerValue)) + "\t"
          + formatCell(and2by2) + "\t"
          + formatCell(or2by2) + "\t"
          + formatCell(wideOr) + "\t"
          + formatCell(contains3);
    }

    private static String formatCell(Stats s) {
      // median as primary, then some compact diagnostics
      return s.median
          + " (p90=" + s.p90
          + ",mean=" + (long) Math.round(s.mean)
          + ",sd=" + (long) Math.round(s.stdev)
          + ",batch=" + s.batch
          + ")";
    }
  }

  private static ResultLine runRoaringSuite(
      String datasetName,
      double bitsPerValue,
      List<RoaringBitmap> bitmaps,
      int maxvalue,
      Config cfg) {

    Stats and2by2 = measure("roaring.and2by2", () -> {
      long acc = 0;
      for (int i = 0; i < bitmaps.size() - 1; i++) {
        acc += RoaringBitmap.and(bitmaps.get(i), bitmaps.get(i + 1)).getCardinality();
      }
      return acc;
    }, cfg);

    Stats or2by2 = measure("roaring.or2by2", () -> {
      long acc = 0;
      for (int i = 0; i < bitmaps.size() - 1; i++) {
        acc += RoaringBitmap.or(bitmaps.get(i), bitmaps.get(i + 1)).getCardinality();
      }
      return acc;
    }, cfg);

    Stats wideOr = measure("roaring.wideOr", () -> {
      return RoaringBitmap.or(bitmaps.iterator()).getCardinality();
    }, cfg);

    Stats contains3 = measure("roaring.contains3", () -> {
      int q1 = maxvalue / 4;
      int q2 = maxvalue / 2;
      int q3 = (3 * maxvalue) / 4;
      long cnt = 0;
      for (RoaringBitmap rb : bitmaps) {
        if (rb.contains(q1)) cnt++;
        if (rb.contains(q2)) cnt++;
        if (rb.contains(q3)) cnt++;
      }
      return cnt;
    }, cfg);

    return new ResultLine(datasetName, "roaring", bitsPerValue, and2by2, or2by2, wideOr, contains3);
  }

  private static ResultLine runBufferSuite(
      String datasetName,
      double bitsPerValue,
      List<ImmutableRoaringBitmap> bitmaps,
      int maxvalue,
      Config cfg) {

    Stats and2by2 = measure("buffer.and2by2", () -> {
      long acc = 0;
      for (int i = 0; i < bitmaps.size() - 1; i++) {
        acc += ImmutableRoaringBitmap.and(bitmaps.get(i), bitmaps.get(i + 1)).getCardinality();
      }
      return acc;
    }, cfg);

    Stats or2by2 = measure("buffer.or2by2", () -> {
      long acc = 0;
      for (int i = 0; i < bitmaps.size() - 1; i++) {
        acc += ImmutableRoaringBitmap.or(bitmaps.get(i), bitmaps.get(i + 1)).getCardinality();
      }
      return acc;
    }, cfg);

    Stats wideOr = measure("buffer.wideOr", () -> {
      return ImmutableRoaringBitmap.or(bitmaps.iterator()).getCardinality();
    }, cfg);

    Stats contains3 = measure("buffer.contains3", () -> {
      int q1 = maxvalue / 4;
      int q2 = maxvalue / 2;
      int q3 = (3 * maxvalue) / 4;
      long cnt = 0;
      for (ImmutableRoaringBitmap rb : bitmaps) {
        if (rb.contains(q1)) cnt++;
        if (rb.contains(q2)) cnt++;
        if (rb.contains(q3)) cnt++;
      }
      return cnt;
    }, cfg);

    return new ResultLine(datasetName, "buffer", bitsPerValue, and2by2, or2by2, wideOr, contains3);
  }

  private static Stats measure(String name, Task task, Config cfg) {
    // Warmup (not measured)
    for (int i = 0; i < cfg.warmupIters; i++) {
      SINK ^= task.run();
    }

    // Calibrate batch size to reduce timer noise
    int batch = calibrateBatch(task, cfg.targetSampleNs);

    // Measurement samples (ns/op)
    long[] samples = new long[cfg.samples];
    for (int i = 0; i < cfg.samples; i++) {
      long acc = 0;
      long start = System.nanoTime();
      for (int j = 0; j < batch; j++) {
        acc ^= task.run();
      }
      long stop = System.nanoTime();
      SINK ^= acc;

      long elapsed = stop - start;
      long nsPerOp = elapsed / batch;
      samples[i] = Math.max(0L, nsPerOp);
    }

    Arrays.sort(samples);
    long median = percentile(samples, 0.50);
    long p90 = percentile(samples, 0.90);
    long min = samples[0];
    long max = samples[samples.length - 1];

    // mean/stdev on raw samples (not sorted-required)
    double mean = 0.0;
    for (long v : samples) mean += v;
    mean /= samples.length;

    double var = 0.0;
    for (long v : samples) {
      double d = v - mean;
      var += d * d;
    }
    var /= samples.length;
    double stdev = Math.sqrt(var);

    return new Stats(batch, samples, mean, stdev, median, p90, min, max);
  }

  private static int calibrateBatch(Task task, long targetNs) {
    // One quick timing to estimate cost
    long acc = 0;
    long start = System.nanoTime();
    acc ^= task.run();
    long stop = System.nanoTime();
    SINK ^= acc;

    long oneOp = Math.max(1L, stop - start);
    long ideal = targetNs / oneOp;

    if (ideal <= 1) return 1;
    if (ideal >= MAX_BATCH) return MAX_BATCH;
    return (int) ideal;
  }

  private static long percentile(long[] sorted, double p) {
    if (sorted.length == 0) return 0L;
    if (p <= 0.0) return sorted[0];
    if (p >= 1.0) return sorted[sorted.length - 1];

    double pos = p * (sorted.length - 1);
    int i = (int) Math.floor(pos);
    int j = Math.min(sorted.length - 1, i + 1);
    double frac = pos - i;
    return (long) Math.round(sorted[i] * (1.0 - frac) + sorted[j] * frac);
  }
}

/**
 * Reads the "real-roaring-dataset" zip format:
 * each ZIP entry contains a single (possibly long) line of comma-separated integers.
 */
class ZipRealDataRetriever {
  private final String dataset;

  ZipRealDataRetriever(String dataset) {
    this.dataset = dataset;
  }

  public List<int[]> fetchBitPositions() throws IOException {
    List<int[]> bitPositions = new ArrayList<>();

    try (final ZipInputStream zis = getResourceAsStream();
         final BufferedReader buf = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8))) {

      while (true) {
        ZipEntry nextEntry = zis.getNextEntry();
        if (nextEntry == null) {
          break;
        }

        String oneLine = buf.readLine(); // expected: a single, perhaps very long, line
        if (oneLine == null || oneLine.isEmpty()) {
          bitPositions.add(new int[0]);
          continue;
        }

        String[] positions = oneLine.split(",");
        int[] ans = new int[positions.length];
        for (int i = 0; i < positions.length; i++) {
          ans[i] = Integer.parseInt(positions[i]);
        }
        bitPositions.add(ans);
      }
    }

    return bitPositions;
  }

  public String getName() {
    return new File(dataset).getName();
  }

  private ZipInputStream getResourceAsStream() throws FileNotFoundException {
    return new ZipInputStream(new FileInputStream(dataset));
  }
}
