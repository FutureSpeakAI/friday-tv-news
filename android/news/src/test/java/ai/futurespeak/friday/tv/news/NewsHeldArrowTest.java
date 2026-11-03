package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.ui.InputGate;

import static org.junit.Assert.*;

/**
 * A held arrow moves one step in Friday News. The TCL remote's recorded key train goes through
 * the app's InputGate (which repeats a held arrow after 400 ms) and then News's own filter
 * ({@link NewsActivity#heldArrow}); what's left is what News acts on.
 */
public class NewsHeldArrowTest {
    private static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, CENTER = 23;

    /** Logical DOWNs that reach News's handlers, per key. */
    private static int[] stepsAfterNews(List<InputGate.Out> outs) {
        int[] steps = new int[300];
        for (InputGate.Out o : outs) if (o.down && !NewsActivity.heldArrow(o.key, true, o.repeat)) steps[o.key]++;
        return steps;
    }

    private static List<InputGate.Out> replay(List<long[]> evs) {
        InputGate g = new InputGate();
        List<InputGate.Out> out = new ArrayList<>();
        long t = 0;
        for (long[] e : evs) {
            t += e[0];
            out.addAll(g.due(t));
            out.addAll(g.feed((int) e[2], e[1] == 1, 0, t));
        }
        out.addAll(g.due(t + 10_000));
        return out;
    }

    @Test public void aTwoSecondHoldOfRightIsOneStep() {
        List<long[]> evs = new ArrayList<>();
        // What the remote sends for a held button: DOWN/UP pairs, ~150 ms down, ~50 ms apart.
        for (int i = 0; i < 10; i++) { evs.add(new long[]{i == 0 ? 0 : 50, 1, RIGHT}); evs.add(new long[]{150, 0, RIGHT}); }
        List<InputGate.Out> outs = replay(evs);
        int repeats = 0;
        for (InputGate.Out o : outs) if (o.down && o.repeat > 0) repeats++;
        assertTrue("the shared layer does repeat a held arrow (so this test means something)", repeats >= 3);
        assertEquals(1, stepsAfterNews(outs)[RIGHT]);
    }

    @Test public void separatePressesStillEachStep() {
        List<long[]> evs = new ArrayList<>();
        for (int i = 0; i < 4; i++) { evs.add(new long[]{i == 0 ? 0 : 350, 1, DOWN}); evs.add(new long[]{120, 0, DOWN}); }
        assertEquals(4, stepsAfterNews(replay(evs))[DOWN]);
    }

    @Test public void theRecordedRemoteGivesOneStepPerHold() throws Exception {
        List<long[]> evs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                NewsHeldArrowTest.class.getResourceAsStream("/tcl_remote_keys.csv"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.replace("﻿", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                int key = keyCode(p[2]);
                if (key < 0) continue;
                evs.add(new long[]{Long.parseLong(p[0]), "DOWN".equals(p[1]) ? 1 : 0, key});
            }
        }
        List<InputGate.Out> outs = replay(evs);
        int[] steps = stepsAfterNews(outs);
        for (int key : new int[]{UP, DOWN, LEFT, RIGHT}) {
            int holds = 0;
            for (InputGate.Out o : outs) if (o.key == key && o.down && o.repeat == 0) holds++;
            assertEquals("one step per hold for key " + key, holds, steps[key]);
        }
        int leftRepeats = 0;
        for (InputGate.Out o : outs) if (o.key == LEFT && o.down && o.repeat > 0) leftRepeats++;
        assertTrue("the recorded held Left makes the shared layer repeat (" + leftRepeats + "), and News drops those", leftRepeats > 0);
    }

    private static int keyCode(String name) {
        switch (name) {
            case "KEYCODE_DPAD_UP": return UP;
            case "KEYCODE_DPAD_DOWN": return DOWN;
            case "KEYCODE_DPAD_LEFT": return LEFT;
            case "KEYCODE_DPAD_RIGHT": return RIGHT;
            case "KEYCODE_DPAD_CENTER": return CENTER;
            default: return -1;
        }
    }
}
