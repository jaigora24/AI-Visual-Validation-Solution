package visual_ai.scripts;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
 
/**
 *  SCRIPT REGISTRY
 *  One place to register and control which scripts run.
 */
public class ScriptRegistry {
 
    // Register all your scripts here
    private static final List<VisualScript> ALL_SCRIPTS = List.of(
            new LoginScript(),
            new HomepageScript() // add new scripts after this line, separated by commas
    );
 
    // Method which return all scripts and using in BaselineCapture / ActualCapture to run everything
    public static List<VisualScript> getAll() {
        return ALL_SCRIPTS;
    }
 
    // Method which return selected or specfic scripts and using in BaselineCapture / ActualCapture to run everything and can run by passing the arguments in that files
    public static List<VisualScript> getByName(String... testNames) {
        List<String> names = Arrays.asList(testNames);
        return ALL_SCRIPTS.stream()
                .filter(s -> names.contains(s.testName()))
                .collect(Collectors.toList());
    }
}
