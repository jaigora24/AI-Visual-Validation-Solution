package visual_ai.scripts;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
 
// Interface which will be implemented by all the scripts for the testName
public interface VisualScript {
 
    // Used as the PNG filename key for both baseline and actual screenshots. Must be unique across all scripts.
    String testName();
 
    // Execute the Automation steps
    void execute(WebDriver driver, WebDriverWait wait) throws Exception;
}
 