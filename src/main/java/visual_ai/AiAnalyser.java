package visual_ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;             
import org.slf4j.LoggerFactory; 

/**
 * ═══════════════════════════════════════════════════════════════
 *  Called automatically by ImageDiffChecker when diff% >= threshold.
 *
 *  Sends 3 images to the AI provider:
 *    baseline + actual + diff for the structured analysis
 *
 *  Switching providers is easy:
 *  Change only the 3 constants in the CONFIG section below:
 *
 *  Claude:
 *    PROVIDER = "claude"
 *    MODEL    = "claude-opus-4-6"
 *    API_URL  = "https://api.anthropic.com/v1/messages"
 *    API_KEY  = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
 *
 *  OpenAI:
 *    PROVIDER = "openai"
 *    MODEL    = "gpt-4o"
 *    API_URL  = "https://api.openai.com/v1/chat/completions"
 *    API_KEY  = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
 *
 *  Gemini:
 *    PROVIDER = "gemini"
 *    MODEL    = "gemini-2.5-flash"
 *    API_URL  = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
 *    API_KEY  = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
 * ═══════════════════════════════════════════════════════════════
 */
public class AiAnalyser {
	
	private static final Logger log = LoggerFactory.getLogger(AiAnalyser.class);
	static Dotenv dotenv = Dotenv.load();

    // ── CONFIG — change these to switch provider ──────────────────────────────
    static final String PROVIDER = "gemini";
    static final String MODEL    = "gemini-2.5-flash";
    static final String API_URL  = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    static final String API_KEY = dotenv.get("GEMINI_API_KEY");
    // ─────────────────────────────────────────────────────────────────────────

    // Max image width before base64 encoding - reduces token cost
    private static final int MAX_IMAGE_WIDTH_PX = 1280;

    private static final ObjectMapper JSON = new ObjectMapper();


    /**
     * Called automatically by ImageDiffChecker when threshold is exceeded.
     *
     * @param testName      test identifier (for display)
     * @param pageUrl       URL that was tested (gives AI context)
     * @param diffPercent   diff% computed by ImageDiffChecker
     * @param baselinePath  path to baseline PNG
     * @param actualPath    path to actual PNG
     * @param diffPath      path to diff highlight PNG
     */
    
    // THIS method will call by ImageDiffChecker when the diff% is greater than the threshold defined in ImageDiffChecker
    public static void analyse(Map<String, String> result,
                               String baselinePath, String actualPath, String diffPath) {

        String testName    = result.get("testName");
        String pageUrl     = result.get("pageUrl");
        double diffPercent = Double.parseDouble(result.get("diffPercent"));

        log.info("  Calling AI (" + PROVIDER + " / " + MODEL + ")...");

        if (API_KEY.isBlank()) {
        	log.warn("[AI SKIPPED] API_KEY not set in AiAnalyser.java");
        	log.warn("Update the API key into the API_KEY constant.");
            result.put("aiSeverity", "UNKNOWN");
            result.put("aiCategory", "api-key-missing");
            result.put("aiDesc",     "API key not configured.");
            result.put("aiRecom",    "Set API_KEY in AiAnalyser.java");
            return;
        }

        try {
            String baselineB64 = toBase64(baselinePath);
            String actualB64   = toBase64(actualPath);
            String diffB64     = toBase64(diffPath);

            String requestBody = buildRequest(testName, pageUrl, diffPercent,
                                              baselineB64, actualB64, diffB64);
            String response    = callApi(requestBody);
            String resultText  = extractText(response);

            // Parse JSON and fill result map
            enrichResult(result, resultText);

        } catch (Exception e) {
        	log.error("  [AI ERROR] " + e.getMessage());
            result.put("aiSeverity", "UNKNOWN");
            result.put("aiCategory", "error");
            result.put("aiDesc",     "AI analysis failed: " + e.getMessage());
            result.put("aiRecom",    "Check API key and network.");
        }
    }

    //Request builders 
    private static String buildRequest(String testName, String pageUrl, double diffPercent,
                                       String baselineB64, String actualB64, String diffB64)
            throws Exception {

        String prompt = buildPrompt(testName, pageUrl, diffPercent);

        return switch (PROVIDER) {
            case "claude" -> buildClaudeRequest(baselineB64, actualB64, diffB64, prompt);
            case "openai" -> buildOpenAiRequest(baselineB64, actualB64, diffB64, prompt);
            case "gemini" -> buildGeminiRequest(baselineB64, actualB64, diffB64, prompt);
            default -> throw new IllegalArgumentException("Unknown provider: " + PROVIDER
                    + ". Valid values: claude, openai, gemini");
        };
    }

    private static String buildPrompt(String testName, String pageUrl, double diffPercent) {
        return """
            You are a visual regression testing expert.

            Test : %s
            URL  : %s
            Diff : %.2f%% of pixels changed

            Three screenshots are attached in order:
              1. BASELINE — the approved reference screenshot
              2. ACTUAL   — captured in this test run
              3. DIFF     — red pixels mark changed areas; grey = unchanged

            Reply with ONLY a valid JSON object, no extra text, no markdown fences:
            {
              "severity":       "NONE | LOW | MEDIUM | HIGH | CRITICAL",
              "category":       "layout shift | missing element | color change | text change | new element | broken image | style regression | other",
              "description":    "1-2 sentences: what changed and where",
              "recommendation": "1-2 sentences: what the developer should investigate"
            }

            Severity guide:
              NONE     — rendering noise, no real visual change
              LOW      — minor cosmetic tweak, no functional impact
              MEDIUM   — noticeable change, may affect usability
              HIGH     — significant regression, likely affects users
              CRITICAL — broken layout or missing critical UI element
            """.formatted(testName, pageUrl, diffPercent);
    }

    // ── Claude ────────────────────────────────────────────────────────────────

    private static String buildClaudeRequest(String b64Baseline, String b64Actual,
                                             String b64Diff, String prompt) throws Exception {
        var root    = JSON.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 2048);

        var messages = root.putArray("messages");
        var userMsg  = messages.addObject().put("role", "user");
        var content  = userMsg.putArray("content");

        addClaudeImage(content, b64Baseline);
        content.addObject().put("type", "text").put("text", "Above: BASELINE screenshot.");
        addClaudeImage(content, b64Actual);
        content.addObject().put("type", "text").put("text", "Above: ACTUAL screenshot.");
        addClaudeImage(content, b64Diff);
        content.addObject().put("type", "text").put("text", "Above: DIFF image (red = changed).");
        content.addObject().put("type", "text").put("text", prompt);

        return JSON.writeValueAsString(root);
    }

    private static void addClaudeImage(com.fasterxml.jackson.databind.node.ArrayNode content,
                                       String base64) {
        var block  = content.addObject().put("type", "image");
        var source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", base64);
    }

    // ── OpenAI ────────────────────────────────────────────────────────────────

    private static String buildOpenAiRequest(String b64Baseline, String b64Actual,
                                             String b64Diff, String prompt) throws Exception {
        var root = JSON.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", 2048);
        root.putObject("response_format").put("type", "json_object");

        var messages = root.putArray("messages");
        var userMsg  = messages.addObject().put("role", "user");
        var content  = userMsg.putArray("content");

        addOpenAiImage(content, b64Baseline);
        content.addObject().put("type", "text").put("text", "Above: BASELINE screenshot.");
        addOpenAiImage(content, b64Actual);
        content.addObject().put("type", "text").put("text", "Above: ACTUAL screenshot.");
        addOpenAiImage(content, b64Diff);
        content.addObject().put("type", "text").put("text", "Above: DIFF image (red = changed).");
        content.addObject().put("type", "text").put("text", prompt);

        return JSON.writeValueAsString(root);
    }

    private static void addOpenAiImage(com.fasterxml.jackson.databind.node.ArrayNode content,
                                       String base64) {
        content.addObject()
               .put("type", "image_url")
               .putObject("image_url")
               .put("url",    "data:image/png;base64," + base64)
               .put("detail", "high");
    }

    // ── Gemini ────────────────────────────────────────────────────────────────

    private static String buildGeminiRequest(String b64Baseline, String b64Actual,
                                             String b64Diff, String prompt) throws Exception {
        var root     = JSON.createObjectNode();
        var contents = root.putArray("contents");
        var parts    = contents.addObject().put("role", "user").putArray("parts");

        addGeminiImage(parts, b64Baseline);
        parts.addObject().put("text", "Above: BASELINE screenshot.");
        addGeminiImage(parts, b64Actual);
        parts.addObject().put("text", "Above: ACTUAL screenshot.");
        addGeminiImage(parts, b64Diff);
        parts.addObject().put("text", "Above: DIFF image (red = changed).");
        parts.addObject().put("text", prompt);

        root.putObject("generationConfig")
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens",  2048);

        return JSON.writeValueAsString(root);
    }

    private static void addGeminiImage(com.fasterxml.jackson.databind.node.ArrayNode parts,
                                       String base64) {
        parts.addObject()
             .putObject("inline_data")
             .put("mime_type", "image/png")
             .put("data", base64);
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private static String callApi(String body) throws IOException, InterruptedException {

        // Gemini: API key goes in URL query param; others use auth header
        String url = PROVIDER.equals("gemini")
                ? API_URL + "?key=" + API_KEY
                : API_URL;

        var reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        switch (PROVIDER) {
            case "claude" -> {
                reqBuilder.header("x-api-key",        API_KEY);
                reqBuilder.header("anthropic-version", "2023-06-01");
            }
            case "openai" -> reqBuilder.header("Authorization", "Bearer " + API_KEY);
            // gemini: auth is in the URL — no header needed
        }

        var client   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        var response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("API error " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    // ── Response text extractor ───────────────────────────────────────────────

    private static String extractText(String responseBody) throws IOException {
        JsonNode root = JSON.readTree(responseBody);

        String text = switch (PROVIDER) {
            case "claude" -> root.path("content").path(0).path("text").asText();
            case "openai" -> root.path("choices").path(0).path("message").path("content").asText();
            case "gemini" -> root.path("candidates").path(0)
                                 .path("content").path("parts").path(0).path("text").asText();
            default -> throw new IllegalArgumentException("Unknown provider: " + PROVIDER);
        };

        if (text == null || text.isBlank()) {
            throw new IOException("Empty response from " + PROVIDER + ": " + responseBody);
        }
        return text.trim();
    }

    // ── Result printer ────────────────────────────────────────────────────────

    /** Parses AI JSON response, fills the result map, and prints to console. */
    private static void enrichResult(Map<String, String> result, String rawText) {
        String cleaned = rawText
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*",     "")
                .trim();
        try {
            JsonNode r = JSON.readTree(cleaned);

            String severity       = r.path("severity").asText("UNKNOWN");
            String category       = r.path("category").asText("unknown");
            String description    = r.path("description").asText("").replaceAll("\\n", " ").trim();
            String recommendation = r.path("recommendation").asText("").replaceAll("\\n", " ").trim();

            // Fill result map for the HTML report
            result.put("aiSeverity", severity);
            result.put("aiCategory", category);
            result.put("aiDesc",     description);
            result.put("aiRecom",    recommendation);

            // Print to console
            String icon = switch (severity.toUpperCase()) {
                case "CRITICAL" -> "\uD83D\uDD34";
                case "HIGH"     -> "\uD83D\uDFE0";
                case "MEDIUM"   -> "\uD83D\uDFE1";
                case "LOW"      -> "\uD83D\uDFE2";
                default         -> "\u26AA";
            };

            log.info("  \u250C\u2500 AI Analysis (" + PROVIDER + " / " + MODEL + ") " + "\u2500".repeat(30));
            log.info("  \u2502  Severity       : " + icon + " " + severity);
            log.info("  \u2502  Category       : " + category);
            log.info("  \u2502  Finding        : " + description);
            log.info("  \u2502  Recommendation : " + recommendation);
            log.info("  \u2514" + "\u2500".repeat(56));

        } catch (Exception e) {
        	log.info("  AI Raw Response: " + rawText);
            result.put("aiSeverity", "UNKNOWN");
            result.put("aiCategory", "parse-error");
            result.put("aiDesc",     rawText);
            result.put("aiRecom",    "");
        }
    }


    // Image helper
    // Loads image, resizes to max MAX_IMAGE_WIDTH_PX wide, returns base64 PNG string
    private static String toBase64(String imagePath) throws IOException {
        BufferedImage img = ImageIO.read(new File(imagePath));
        if (img == null) throw new IOException("Cannot read image: " + imagePath);

        if (img.getWidth() > MAX_IMAGE_WIDTH_PX) {
            double scale = (double) MAX_IMAGE_WIDTH_PX / img.getWidth();
            int tw = MAX_IMAGE_WIDTH_PX;
            int th = (int) Math.round(img.getHeight() * scale);

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
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}