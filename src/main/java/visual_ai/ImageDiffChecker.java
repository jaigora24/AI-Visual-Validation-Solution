package visual_ai;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;             
import org.slf4j.LoggerFactory; 

/**
 * ═══════════════════════════════════════════════════════════════
 *  IMAGE DIFF CHECKER
 *  Called automatically by ActualCapture after every snapshot().
 *  Do NOT run this directly.
 *
 *  What it does:
 *    1. Loads baseline PNG + actual PNG
 *    2. Compares every pixel → computes diff%
 *    3. Saves diff image (red = changed, grey = unchanged)
 *       + draws yellow bounding boxes around changed regions
 *    4. diff% < THRESHOLD  → PASS
 *    5. diff% >= THRESHOLD → calls AiAnalyser automatically
 *    6. Adds result map to RESULTS list for HtmlReportGenerator
 * ═══════════════════════════════════════════════════════════════
 */
public class ImageDiffChecker {
	
	private static final Logger log = LoggerFactory.getLogger(ImageDiffChecker.class);

    static final double DIFF_THRESHOLD_PERCENT = 2.0;
    static final int    CHANNEL_TOLERANCE      = 10;
    private static final int MIN_REGION_SIZE   = 50;  // min px to draw a bounding box
    private static final int BOX_PADDING       = 8;   // padding around each box

    /**
     * Collected results across all snapshot() calls — read by HtmlReportGenerator.
     * Key fields in each map:
     *   testName, pageUrl, baselinePath, actualPath, diffPath,
     *   diffPercent, diffPixels, totalPixels, status,
     *   aiSeverity, aiCategory, aiDescription, aiRecommendation, aiProvider, aiModel
     */
    static final List<Map<String, String>> RESULTS = new ArrayList<>();

    public static void check(String testName, String pageUrl, String actualPath)
            throws IOException {

        String baselinePath = BaselineCapture.BASELINE_DIR + "/" + testName + "_baseline.png";
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String diffPath = ActualCapture.DIFF_DIR + "/" + testName + "_" + timestamp + "_diff.png";

        // IMPORTANT to check baseline must exist for comparison
        if (!new File(baselinePath).exists()) {
        	log.error("[SKIP] No baseline: " + baselinePath);
        	log.warn("Run BaselineCapture first.");
            addResult(testName, pageUrl, baselinePath, actualPath, diffPath,
                      0, 0, 0, "ERROR", null, null, null, null);
            return;
        }

        BufferedImage baseline = ImageIO.read(new File(baselinePath));
        BufferedImage actual   = ImageIO.read(new File(actualPath));

        // Normalise dimensions
        if (baseline.getWidth() != actual.getWidth()
                || baseline.getHeight() != actual.getHeight()) {
            System.out.printf("  [WARN] Size mismatch %dx%d vs %dx%d — normalising%n",
                    baseline.getWidth(), baseline.getHeight(),
                    actual.getWidth(),   actual.getHeight());
            actual = resize(actual, baseline.getWidth(), baseline.getHeight());
        }

        int  w           = baseline.getWidth();
        int  h           = baseline.getHeight();
        long totalPixels = (long) w * h;

        //Pixel comparison
        boolean[][] changedMap = new boolean[h][w];
        BufferedImage diffImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        long diffCount = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (isDifferent(baseline.getRGB(x, y), actual.getRGB(x, y))) {
                    diffImage.setRGB(x, y, 0xFFFF0000); // red = changed
                    changedMap[y][x] = true;
                    diffCount++;
                } else {
                    diffImage.setRGB(x, y, dimmed(baseline.getRGB(x, y))); // grey = unchanged
                }
            }
        }

        //Draw bounding boxes around changed regions
        List<Rectangle> regions = findChangedRegions(changedMap, w, h);
        Graphics2D g = diffImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (Rectangle region : regions) {
            g.setColor(new Color(255, 220, 0, 255));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(region.x, region.y, region.width, region.height);
            g.setColor(new Color(255, 50, 50, 200));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(region.x + 2, region.y + 2, region.width - 4, region.height - 4);
            drawLabel(g, "CHANGED", region.x, region.y);
        }
        g.dispose();

        //Save diff image
        new File(ActualCapture.DIFF_DIR).mkdirs();
        ImageIO.write(diffImage, "PNG", new File(diffPath));

        double diffPercent = (diffCount * 100.0) / totalPixels;

        // Generating logs
        log.info("  ┌─ Diff: {}", testName);
        log.info("  │  Changed  : {} / {} px  ({} %)", diffCount, totalPixels, String.format("%.2f", diffPercent));
        log.info("  │  Regions  : {} bounding box(es) drawn", regions.size());
        log.info("  │  Threshold: {} %", DIFF_THRESHOLD_PERCENT);
        log.info("  │  Diff img : " + diffPath);

        // Threshold check to decide PASS/FAIL and whether to trigger AI analysis
        if (diffPercent < DIFF_THRESHOLD_PERCENT) {

            log.info("  │  Result   : PASS");
            log.info("  └─ AI skipped — diff below threshold, SKIP AI analysis");
            addResult(testName, pageUrl, baselinePath, actualPath, diffPath,
                      diffPercent, diffCount, totalPixels, "PASS",
                      null, null, null, null);

        } else {
            log.warn("  │  Result   : FAIL — diff {} % >= threshold {} %",
                    String.format("%.2f", diffPercent), DIFF_THRESHOLD_PERCENT);
            log.info("  └─ Triggering AI agent for analysis...");

            Map<String, String> result = addResult(testName, pageUrl,
                    baselinePath, actualPath, diffPath,
                    diffPercent, diffCount, totalPixels, "FAIL",
                    null, null, null, null);

            AiAnalyser.analyse(result, baselinePath, actualPath, diffPath);
        }
    }

    // Result map helper
    private static Map<String, String> addResult(
            String testName, String pageUrl,
            String baselinePath, String actualPath, String diffPath,
            double diffPercent, long diffPixels, long totalPixels, String status,
            String aiSeverity, String aiCategory, String aiDescription, String aiRecom) {

        Map<String, String> r = new HashMap<>();
        r.put("testName",     testName);
        r.put("pageUrl",      pageUrl);
        r.put("baselinePath", baselinePath);
        r.put("actualPath",   actualPath);
        r.put("diffPath",     diffPath);
        r.put("diffPercent",  String.format("%.2f", diffPercent));
        r.put("diffPixels",   String.valueOf(diffPixels));
        r.put("totalPixels",  String.valueOf(totalPixels));
        r.put("threshold",    String.valueOf(DIFF_THRESHOLD_PERCENT));
        r.put("status",       status);
        r.put("aiSeverity",   aiSeverity   != null ? aiSeverity   : "");
        r.put("aiCategory",   aiCategory   != null ? aiCategory   : "");
        r.put("aiDesc",       aiDescription!= null ? aiDescription: "");
        r.put("aiRecom",      aiRecom      != null ? aiRecom      : "");
        r.put("aiProvider",   AiAnalyser.PROVIDER);
        r.put("aiModel",      AiAnalyser.MODEL);
        RESULTS.add(r);
        return r;
    }

    // Bounding box detection 
    private static List<Rectangle> findChangedRegions(boolean[][] map, int w, int h) {
        boolean[][] visited = new boolean[h][w];
        List<Rectangle> regions = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (map[y][x] && !visited[y][x]) {
                    Rectangle bounds = floodFill(map, visited, x, y, w, h);
                    if (bounds != null && bounds.width * bounds.height >= MIN_REGION_SIZE) {
                        int px = Math.max(0, bounds.x - BOX_PADDING);
                        int py = Math.max(0, bounds.y - BOX_PADDING);
                        int pw = Math.min(w - px, bounds.width  + BOX_PADDING * 2);
                        int ph = Math.min(h - py, bounds.height + BOX_PADDING * 2);
                        regions.add(new Rectangle(px, py, pw, ph));
                    }
                }
            }
        }
        return mergeOverlapping(regions);
    }

    private static Rectangle floodFill(boolean[][] map, boolean[][] visited,
                                        int startX, int startY, int w, int h) {
        java.util.Queue<int[]> queue = new java.util.LinkedList<>();
        queue.add(new int[]{startX, startY});
        int minX = startX, maxX = startX, minY = startY, maxY = startY, count = 0;
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int cx = p[0], cy = p[1];
            if (cx < 0 || cx >= w || cy < 0 || cy >= h || visited[cy][cx] || !map[cy][cx]) continue;
            visited[cy][cx] = true;
            minX = Math.min(minX, cx); maxX = Math.max(maxX, cx);
            minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
            count++;
            queue.add(new int[]{cx+1,cy}); queue.add(new int[]{cx-1,cy});
            queue.add(new int[]{cx,cy+1}); queue.add(new int[]{cx,cy-1});
        }
        return count == 0 ? null : new Rectangle(minX, minY, maxX-minX+1, maxY-minY+1);
    }

    private static List<Rectangle> mergeOverlapping(List<Rectangle> rects) {
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < rects.size(); i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    Rectangle expanded = new Rectangle(
                            rects.get(i).x - 20, rects.get(i).y - 20,
                            rects.get(i).width + 40, rects.get(i).height + 40);
                    if (expanded.intersects(rects.get(j))) {
                        Rectangle union = rects.get(i).union(rects.get(j));
                        rects.remove(j); rects.remove(i); rects.add(union);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return rects;
    }

    private static void drawLabel(Graphics2D g, String text, int x, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 8, th = fm.getHeight() + 2;
        int lx = Math.max(0, x), ly = Math.max(th, y) - th;
        g.setColor(new Color(220, 50, 50, 230));
        g.fillRoundRect(lx, ly, tw, th, 4, 4);
        g.setColor(Color.WHITE);
        g.drawString(text, lx + 4, ly + fm.getAscent());
    }

    // Pixel helpers
    private static boolean isDifferent(int rgb1, int rgb2) {
        return Math.abs(red(rgb1)   - red(rgb2))   > CHANNEL_TOLERANCE
            || Math.abs(green(rgb1) - green(rgb2)) > CHANNEL_TOLERANCE
            || Math.abs(blue(rgb1)  - blue(rgb2))  > CHANNEL_TOLERANCE;
    }

    private static int dimmed(int rgb) {
        int r = (int)(red(rgb)   * 0.35 + 160 * 0.65);
        int g = (int)(green(rgb) * 0.35 + 160 * 0.65);
        int b = (int)(blue(rgb)  * 0.35 + 160 * 0.65);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >>  8) & 0xFF; }
    private static int blue(int rgb)  { return  rgb        & 0xFF; }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}