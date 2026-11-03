package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Friday News takes keys only through the app's one input layer (ui/InputLayer), so a remote's
 * burst is one press here too, and no WebView in News can handle a key a second time.
 */
public class NewsInputGuardTest {
    private static final File DIR = new File("src/main/java/ai/futurespeak/friday/tv/news");

    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** The source without comments, so a comment that says "no WebView" doesn't count as one. */
    private static String code(File f) throws Exception {
        return read(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test public void everyRawKeyGoesThroughTheInputLayer() throws Exception {
        File[] files = DIR.listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull(files);
        int activities = 0;
        for (File f : files) {
            String s = read(f);
            Matcher m = Pattern.compile("boolean dispatchKeyEvent\\(KeyEvent \\w+\\)\\s*\\{([^}]*)}").matcher(s);
            while (m.find()) {
                activities++;
                assertEquals(f.getName() + " must hand raw keys to the input layer and do nothing else",
                        "return input.feed(e);", m.group(1).trim());
                assertTrue(f.getName(), s.contains("new ai.futurespeak.friday.tv.ui.InputLayer(this::route)"));
                assertTrue(f.getName() + " must reset the layer when focus is lost", s.contains("input.reset()"));
            }
            assertFalse(f.getName() + ": keys reach News only through the input layer",
                    s.contains("onKeyDown(") || s.contains("onKeyUp(") || s.contains("setOnKeyListener("));
        }
        assertEquals("NewsActivity is News's only key entry point", 1, activities);
    }

    @Test public void heldArrowsAreDroppedBeforeAnyHandler() throws Exception {
        String activity = code(new File(DIR, "NewsActivity.java"));
        java.util.regex.Matcher m = Pattern.compile("private boolean route\\(KeyEvent e\\) \\{(.*?)Layer top =", Pattern.DOTALL).matcher(activity);
        assertTrue("route() exists", m.find());
        assertTrue("route() drops held-arrow repeats before layers or views see them", m.group(1).contains("if (heldArrow(e)) return true;"));
    }

    @Test public void newsHasNoWebView() throws Exception {
        for (File f : DIR.listFiles((d, n) -> n.endsWith(".java"))) {
            String s = code(f);
            assertFalse(f.getName(), s.contains("WebView") || s.contains("SandboxView") || s.contains("CardView"));
        }
    }

    @Test public void oneShotActionsIgnoreHeldRepeats() throws Exception {
        String autoplay = read(new File(DIR, "AutoplayLayer.java"));
        assertTrue("autoplay skips once per press", autoplay.contains("e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0"));
        String activity = read(new File(DIR, "NewsActivity.java"));
        assertTrue("Back acts once per press",
                Pattern.compile("down && e\\.getRepeatCount\\(\\) == 0\\)\\s*\\{\\s*if \\(top != null\\) top\\.back\\(\\);").matcher(activity).find());
    }
}
