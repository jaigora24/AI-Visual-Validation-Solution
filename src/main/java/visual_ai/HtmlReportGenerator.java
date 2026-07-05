package visual_ai;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;             
import org.slf4j.LoggerFactory; 

/**
 * ═══════════════════════════════════════════════════════════════
 *  HTML REPORT GENERATOR
 *  Called once at the end of ActualCapture with one line:
 *
 *    HtmlReportGenerator.generate(ImageDiffChecker.RESULTS);
 *
 *  Reads the list of result maps collected by ImageDiffChecker
 *  and writes a single portable HTML file to reports/ folder.
 *  Images are base64-embedded — no broken links when sharing.
 * ═══════════════════════════════════════════════════════════════
 */
public class HtmlReportGenerator {
	
	private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);

    static final String REPORT_DIR = "reports";

    public static void generate(List<Map<String, String>> results) throws IOException {
        if (results.isEmpty()) {
        	log.info("  [Report] No results to report.");
            return;
        }

        Files.createDirectories(Paths.get(REPORT_DIR));
        String timestamp  = ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String reportPath = REPORT_DIR + "/visual-report_" + timestamp + ".html";
        String runTime    = ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z"));

        // Summary counts
        long total    = results.size();
        long passed   = results.stream().filter(r -> "PASS".equals(r.get("status"))).count();
        long failed   = results.stream().filter(r -> "FAIL".equals(r.get("status"))).count();
        long aiRun    = results.stream().filter(r -> !r.get("aiSeverity").isEmpty()).count();

        StringBuilder html = new StringBuilder();
        html.append(head());
        html.append(banner(total, passed, failed, aiRun, runTime));

        for (Map<String, String> r : results) {
            html.append(card(r));
        }

        html.append("</div></body></html>");

        try (FileWriter fw = new FileWriter(reportPath)) {
            fw.write(html.toString());
        }

        log.info("  📄 HTML Report → " + reportPath);
        log.info("     Open in any browser — images embedded, fully portable.");
    }

    // Card per test 
    private static String card(Map<String, String> r) {
        String status    = r.get("status");
        boolean isPass   = "PASS".equals(status);
        String  cssClass = isPass ? "card-pass" : "card-fail";
        String  badge    = isPass
                ? "<span class='badge b-pass'>PASS</span>"
                : "<span class='badge b-fail'>FAIL</span>";

        double diffPct  = Double.parseDouble(r.get("diffPercent"));
        String barColor = diffPct < 2.0 ? "#22c55e" : diffPct < 10.0 ? "#f59e0b" : "#ef4444";
        double barWidth = Math.min(100.0, diffPct * 5);

        return """
            <details class='card %s' %s>
              <summary>
                %s
                <span class='tname'>%s</span>
                <span class='url'>%s</span>
                <span class='pct' style='color:%s'>%s%%</span>
              </summary>
              <div class='cbody'>
                <div class='diff-bar-wrap'>
                  <div class='diff-bar' style='width:%.1f%%;background:%s'></div>
                  <span class='diff-bar-label'>%s%% pixels changed
                    &nbsp;·&nbsp; %s / %s px
                    &nbsp;·&nbsp; threshold %s%%</span>
                </div>
                <div class='imgs'>
                  <figure>
                    <figcaption><span class='dot dot-blue'></span>Baseline</figcaption>
                    %s
                  </figure>
                  <figure>
                    <figcaption><span class='dot dot-green'></span>Actual</figcaption>
                    %s
                  </figure>
                  <figure>
                    <figcaption><span class='dot dot-red'></span>Diff
                      <span class='fig-hint'>(red = changed · yellow box = region)</span>
                    </figcaption>
                    %s
                  </figure>
                </div>
                %s
              </div>
            </details>
            """.formatted(
                cssClass, isPass ? "" : "open",
                badge,
                r.get("testName"),
                r.get("pageUrl"),
                barColor, r.get("diffPercent"),
                barWidth, barColor,
                r.get("diffPercent"), r.get("diffPixels"), r.get("totalPixels"), r.get("threshold"),
                embedImage(r.get("baselinePath")),
                embedImage(r.get("actualPath")),
                embedImage(r.get("diffPath")),
                aiBlock(r)
        );
    }

    private static String aiBlock(Map<String, String> r) {
        String sev = r.get("aiSeverity");
        if (sev == null || sev.isEmpty()) {
            return "<div class='no-ai'>✅ Diff below threshold — AI skipped (no cost incurred)</div>";
        }

        String sevCss = switch (sev.toUpperCase()) {
            case "CRITICAL" -> "sev-critical";
            case "HIGH"     -> "sev-high";
            case "MEDIUM"   -> "sev-medium";
            case "LOW"      -> "sev-low";
            default         -> "sev-none";
        };
        String icon = switch (sev.toUpperCase()) {
            case "CRITICAL" -> "🔴";
            case "HIGH"     -> "🟠";
            case "MEDIUM"   -> "🟡";
            case "LOW"      -> "🟢";
            default         -> "⚪";
        };

        return """
            <div class='ai-block'>
              <div class='ai-hdr'>
                <span class='ai-label'>AI Analysis</span>
                <span class='sev %s'>%s %s</span>
                <span class='cat'>%s</span>
                <span class='ai-provider'>via %s / %s</span>
              </div>
              <div class='ai-row'>
                <span class='ai-field'>Finding</span>
                <span class='ai-val'>%s</span>
              </div>
              <div class='ai-row'>
                <span class='ai-field'>Recommendation</span>
                <span class='ai-val rec'>%s</span>
              </div>
            </div>
            """.formatted(
                sevCss, icon, sev,
                r.getOrDefault("aiCategory", ""),
                r.getOrDefault("aiProvider", ""),
                r.getOrDefault("aiModel",    ""),
                r.getOrDefault("aiDesc",     "").replace("\n", " "),
                r.getOrDefault("aiRecom",    "").replace("\n", " ")
        );
    }

    // Embed image as base64
    private static String embedImage(String path) {
        if (path == null || !new File(path).exists()) {
            return "<div class='no-img'>Image not available</div>";
        }
        try {
            BufferedImage img = ImageIO.read(new File(path));
            if (img == null) return "<div class='no-img'>Cannot read image</div>";

            // Resize to max 600px wide — keeps HTML file size manageable
            if (img.getWidth() > 600) {
                int tw = 600;
                int th = (int) Math.round(img.getHeight() * (600.0 / img.getWidth()));
                BufferedImage resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, 0, 0, tw, th, null);
                g.dispose();
                img = resized;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "<img src='data:image/png;base64," + b64 + "' loading='lazy'/>";

        } catch (IOException e) {
            return "<div class='no-img'>Error: " + e.getMessage() + "</div>";
        }
    }

    // HTML + CSS
    private static String banner(long total, long passed, long failed,
                                  long aiRun, String runTime) {
        String overall = failed > 0
                ? "<span class='overall fail'>❌ FAILED</span>"
                : "<span class='overall pass'>✅ ALL PASSED</span>";
        return """
            <div class='header'>
              <div>
                <h1>AI Visual Regression Report</h1>
                <p class='meta'>%s &nbsp;·&nbsp; AI: <strong>%s / %s</strong></p>
              </div>
              <div>%s</div>
            </div>
            <div class='stats'>
              <div class='stat'><div class='sn'>%d</div><div class='sl'>Total</div></div>
              <div class='stat'><div class='sn' style='color:#22c55e'>%d</div><div class='sl'>Passed</div></div>
              <div class='stat'><div class='sn' style='color:#ef4444'>%d</div><div class='sl'>Failed</div></div>
              <div class='stat'><div class='sn' style='color:#a78bfa'>%d</div><div class='sl'>AI analysed</div></div>
            </div>
            <div class='cards'>
            """.formatted(runTime, AiAnalyser.PROVIDER, AiAnalyser.MODEL,
                          overall, total, passed, failed, aiRun);
    }

    private static String head() {
        return """
            <!DOCTYPE html><html lang='en'><head>
            <meta charset='UTF-8'/>
            <meta name='viewport' content='width=device-width,initial-scale=1'/>
            <title>Visual Regression Report</title>
            <style>
            *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
            body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;padding:24px}
            h1{font-size:1.4rem;font-weight:600;margin-bottom:2px}
            .meta{font-size:.75rem;color:#64748b}
            .header{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:20px;flex-wrap:wrap;gap:10px}
            .overall{font-size:.95rem;font-weight:600;padding:6px 14px;border-radius:8px}
            .overall.pass{background:#14532d;color:#86efac}.overall.fail{background:#7f1d1d;color:#fca5a5}
            .stats{display:flex;gap:12px;margin-bottom:24px;flex-wrap:wrap}
            .stat{background:#1e293b;border-radius:10px;padding:12px 18px;min-width:90px;text-align:center}
            .sn{font-size:1.8rem;font-weight:700}.sl{font-size:.7rem;color:#64748b;margin-top:2px}
            .cards{display:flex;flex-direction:column;gap:14px}
            .card{background:#1e293b;border-radius:12px;overflow:hidden}
            .card-pass>summary{border-left:4px solid #22c55e}
            .card-fail>summary{border-left:4px solid #ef4444}
            summary{display:flex;align-items:center;gap:10px;padding:12px 16px;cursor:pointer;list-style:none;flex-wrap:wrap}
            summary::-webkit-details-marker{display:none}
            .badge{font-size:.65rem;font-weight:700;padding:2px 7px;border-radius:5px;text-transform:uppercase}
            .b-pass{background:#14532d;color:#86efac}.b-fail{background:#7f1d1d;color:#fca5a5}
            .tname{font-weight:600;font-size:.92rem}.url{font-size:.7rem;color:#475569;margin-right:auto}
            .pct{font-size:.82rem;font-weight:600}
            .cbody{padding:0 16px 16px}
            .diff-bar-wrap{margin:0 0 12px}.diff-bar{height:5px;border-radius:3px}
            .diff-bar-label{font-size:.68rem;color:#64748b;display:block;margin-top:3px}
            .imgs{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}
            figure{background:#0f172a;border-radius:8px;overflow:hidden}
            figcaption{font-size:.68rem;color:#94a3b8;padding:6px 9px;display:flex;align-items:center;gap:5px}
            .fig-hint{color:#475569;font-style:italic}
            img{width:100%;display:block}
            .dot{width:7px;height:7px;border-radius:50%;display:inline-block;flex-shrink:0}
            .dot-blue{background:#3b82f6}.dot-green{background:#22c55e}.dot-red{background:#ef4444}
            .ai-block{background:#0f172a;border-radius:8px;padding:12px 14px;border:0.5px solid #1e3a5f}
            .ai-hdr{display:flex;align-items:center;gap:8px;margin-bottom:10px;flex-wrap:wrap}
            .ai-label{font-size:.65rem;font-weight:700;text-transform:uppercase;color:#475569}
            .sev{font-size:.68rem;font-weight:700;padding:2px 8px;border-radius:5px}
            .sev-critical{background:#7f1d1d;color:#fca5a5}.sev-high{background:#7c2d12;color:#fdba74}
            .sev-medium{background:#713f12;color:#fde68a}.sev-low{background:#14532d;color:#86efac}
            .sev-none{background:#1e293b;color:#94a3b8}
            .cat{font-size:.75rem;color:#94a3b8;font-style:italic}
            .ai-provider{font-size:.65rem;color:#334155;margin-left:auto}
            .ai-row{display:flex;gap:10px;margin-top:6px;font-size:.82rem;line-height:1.55}
            .ai-field{color:#64748b;min-width:110px;flex-shrink:0;font-size:.75rem}
            .ai-val{color:#cbd5e1}.ai-val.rec{color:#93c5fd}
            .no-ai{font-size:.78rem;color:#4ade80;padding:7px 0}
            .no-img{font-size:.75rem;color:#475569;padding:20px;text-align:center;background:#0f172a;border-radius:8px}
            @media(max-width:700px){.imgs{grid-template-columns:1fr}}
            </style></head><body>
            """;
    }
}