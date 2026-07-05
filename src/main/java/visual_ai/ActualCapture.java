package visual_ai;

import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import visual_ai.scripts.ScriptRegistry;
import visual_ai.scripts.VisualScript;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 *  This is the ACTUAL CAPTURE which will run the visual scripts and save the actual screenshots.
 */
public class ActualCapture extends BaselineCapture {

    private static final Logger log = LoggerFactory.getLogger(ActualCapture.class);

    static final String ACTUAL_DIR = "actuals";
    static final String DIFF_DIR   = "diffs";

    public static void main(String[] args) throws Exception {
        new ActualCapture().run(
            ScriptRegistry.getAll()                                          // run ALL scripts
            // ScriptRegistry.getByName("demo-shop-login", "demo-shop-cart") // run SELECTED only
        );
    }

    @Override
    void run(List<VisualScript> scripts) throws Exception {
        ImageDiffChecker.RESULTS.clear();
        // Creating directories for logs, actuals, diff if not exist
        new File("logs").mkdirs();
        new File(ACTUAL_DIR).mkdirs();
        new File(DIFF_DIR).mkdirs();

        log.info("════════════════════════════════════════════════════════════");
        log.info("ACTUAL CAPTURE started — {} script(s) to run", scripts.size());
        log.info("════════════════════════════════════════════════════════════");

        // Looping if we have more than 1 script, otherwise just run the single one
        for (VisualScript script : scripts) {
            log.info("Starting script: {} ...", script.testName());

            // Fresh browser per script
            driver = createDriver();
            wait   = new WebDriverWait(driver, Duration.ofSeconds(10));

            try {
                script.execute(driver, wait);
                snapshot(script.testName());
            } catch (Exception e) {
                log.error("Script failed [{}]: {}", script.testName(), e.getMessage(), e);
            } finally {
                driver.quit();  // always close after each script
                log.info("Browser closed: {}", script.testName());
            }
        }

        // Generate single HTML report for all results
        HtmlReportGenerator.generate(ImageDiffChecker.RESULTS);

        log.info("════════════════════════════════════════════════════════════");
        log.info("ACTUAL CAPTURE complete");
        log.info("════════════════════════════════════════════════════════════");
    }

    // THis method is comparing the actual snapshot with the baseline and save the diff if any.
    @Override
    protected void snapshot(String testName) throws IOException {
        String timestamp  = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String actualPath = ACTUAL_DIR + "/" + testName + "_" + timestamp + "_actual.png";

        captureToPath(actualPath);
        log.info("[actual] Saved → {}", actualPath);

        ImageDiffChecker.check(testName, driver.getCurrentUrl(), actualPath);
    }

    // Capture the screenshot of the current page and save it to the specified path
    private void captureToPath(String savePath) throws IOException {
        var screenshot = new ru.yandex.qatools.ashot.AShot()
                .shootingStrategy(
                    ru.yandex.qatools.ashot.shooting.ShootingStrategies
                        .viewportPasting(SCROLL_TIMEOUT))
                .takeScreenshot(driver);

        File out = new File(savePath);
        out.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(screenshot.getImage(), "PNG", out);
    }
}