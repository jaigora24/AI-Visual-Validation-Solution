package visual_ai;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.yandex.qatools.ashot.AShot;
import ru.yandex.qatools.ashot.shooting.ShootingStrategies;
import visual_ai.scripts.ScriptRegistry;
import visual_ai.scripts.VisualScript;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 *  BASELINE CAPTURE
 *  Run ONCE to save reference screenshots.
 *  Re-run only when a UI change is intentional and approved.
 */
public class BaselineCapture {

    private static final Logger log = LoggerFactory.getLogger(BaselineCapture.class);

    static final String  BASELINE_DIR   = "baselines";
    static final int     SCROLL_TIMEOUT = 100;
    static final boolean HEADLESS       = true;
    static final int     WINDOW_WIDTH   = 1440;
    static final int     WINDOW_HEIGHT  = 900;

    protected WebDriver driver;
    protected WebDriverWait wait;

    public static void main(String[] args) throws Exception {
        new BaselineCapture().run(

            ScriptRegistry.getAll()                                          // run ALL scripts
            // ScriptRegistry.getByName("demo-shop-login", "demo-shop-cart") // run SELECTED only

        );
    }

    void run(List<VisualScript> scripts) throws Exception {
        new File("logs").mkdirs();
        new File(BASELINE_DIR).mkdirs();

        log.info("════════════════════════════════════════════════════════════");
        log.info("BASELINE CAPTURE started — {} script(s) to run", scripts.size());
        log.info("════════════════════════════════════════════════════════════");

        for (VisualScript script : scripts) {
            log.info("Starting Automation script: {}...", script.testName());

            // Fresh browser per script
            driver = createDriver();
            wait   = new WebDriverWait(driver, Duration.ofSeconds(10));

            try {
                script.execute(driver, wait);
                snapshot(script.testName());
            } catch (Exception e) {
                log.error("Automation Script failed [{}]: {}", script.testName(), e.getMessage(), e);
            } finally {
                driver.quit();
                log.info("Browser closed: {}", script.testName());
            }
        }
    }

    // this method will called after each script execution to capture and save the baseline screenshot
    protected void snapshot(String testName) throws IOException {
        String savePath = BASELINE_DIR + "/" + testName + "_baseline.png";

        var screenshot = new AShot()
                .shootingStrategy(ShootingStrategies.viewportPasting(SCROLL_TIMEOUT))
                .takeScreenshot(driver);

        File out = new File(savePath);
        out.getParentFile().mkdirs();
        ImageIO.write(screenshot.getImage(), "PNG", out);

        log.info("[baseline] Saved → {} ({}x{}px)",
                savePath,
                screenshot.getImage().getWidth(),
                screenshot.getImage().getHeight());
    }

    // This method is creating a new ChromeDriver instance with specified options (headless, window size, etc.)
    static WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        if (HEADLESS) opts.addArguments("--headless=new");
        opts.addArguments("--window-size=" + WINDOW_WIDTH + "," + WINDOW_HEIGHT);
        opts.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        log.info("Starting Chrome (headless={})", HEADLESS);
        return new ChromeDriver(opts);
    }

    // Utility method to pause execution for a given number of milliseconds
    static void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}