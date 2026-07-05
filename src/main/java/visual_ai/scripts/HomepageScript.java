package visual_ai.scripts;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
 
public class HomepageScript implements VisualScript {
 
    @Override
    public String testName() {
        return "demo-shop-homepage";
    }
 
    @Override
    public void execute(WebDriver driver, WebDriverWait wait) throws Exception {
        driver.get("https://demowebshop.tricentis.com/");
        Thread.sleep(1500);
    }
}
 
