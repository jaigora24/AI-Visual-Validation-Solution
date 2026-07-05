package visual_ai.scripts;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
 
public class LoginScript implements VisualScript {
 
    private static final String EMAIL    = "aidemo@email.com";  
    private static final String PASSWORD = "aidemo123";     
 
    @Override
    public String testName() {
        return "demo-shop-login";
    }
 
    @Override
    public void execute(WebDriver driver, WebDriverWait wait) throws Exception {
        driver.get("https://demowebshop.tricentis.com/login");
        Thread.sleep(1500);
 
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("Email")))
            .sendKeys(EMAIL);
        driver.findElement(By.id("Password")).sendKeys(PASSWORD);
        driver.findElement(By.cssSelector("input[value='Log in']")).click();
 
        wait.until(ExpectedConditions.urlContains("tricentis.com/"));
        Thread.sleep(1500);
 
        // ← snapshot() is called automatically by the runner after execute() returns
    }
}
 