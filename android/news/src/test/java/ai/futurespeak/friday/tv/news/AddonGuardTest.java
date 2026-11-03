package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** News is a Friday TV add-on: no keys, no third-party runtime libraries, no launcher entry, contract v1. */
public class AddonGuardTest {
    static String read(String p) throws Exception { return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8); }

    @Test public void neverHoldsAKey() throws Exception {
        for (File f : new File("src/main/java/ai/futurespeak/friday/tv/news").listFiles()) {
            String s = read(f.getPath());
            for (String bad : new String[]{"xi-api-key", "x-api-key", "SecureStore", "api.elevenlabs.io", "api.anthropic.com", "Authorization"})
                assertFalse(f.getName() + " mentions " + bad, s.contains(bad));
        }
    }

    @Test public void onlyOurOwnSdkAtRuntime() throws Exception {
        String g = read("build.gradle").replaceAll("//[^\\n]*", "");
        assertFalse(Pattern.compile("(?m)^\\s*implementation\\s(?!project\\(':addon-sdk'\\))").matcher(g).find());
    }

    @Test public void declaresContractOneAndNoLauncherEntry() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        assertTrue(m.contains("ai.futurespeak.friday.tv.addon.OPEN"));
        assertFalse("opens from Friday, not the launcher", m.contains("LEANBACK_LAUNCHER") || m.contains("category.LAUNCHER"));
        String d = read("src/main/res/xml/friday_addon.xml");
        assertTrue(d.contains("contract=\"1\""));
    }
}
