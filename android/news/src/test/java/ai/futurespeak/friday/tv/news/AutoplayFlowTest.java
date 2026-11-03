package ai.futurespeak.friday.tv.news;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static ai.futurespeak.friday.tv.news.AutoplayFlow.Mode.LISTEN;
import static ai.futurespeak.friday.tv.news.AutoplayFlow.Mode.PLAY;
import static ai.futurespeak.friday.tv.news.AutoplayFlow.Timer.ADVANCE;
import static ai.futurespeak.friday.tv.news.AutoplayFlow.Timer.WATCHDOG;
import static org.junit.Assert.*;

/** Autoplay's decisions, including the TV's "got ahead of itself and cut itself off" race. */
public class AutoplayFlowTest {
    /** Records what the flow asks for. */
    static final class Rec implements AutoplayFlow.Out {
        final List<String> log = new ArrayList<>();
        final List<long[]> timers = new ArrayList<>();   // token, kind ordinal, ms
        final List<Long> spoken = new ArrayList<>();
        int shown = -1, stops = 0, finished = 0;
        @Override public void show(int i) { shown = i; log.add("show " + i); }
        @Override public void speak(long t, int i) { spoken.add(t); log.add("speak " + i + " t" + t); }
        @Override public void stopSpeech() { stops++; }
        @Override public void prefetch(int i) { log.add("prefetch " + i); }
        @Override public void startTimer(long t, AutoplayFlow.Timer k, long ms) { timers.add(new long[]{t, k.ordinal(), ms}); log.add("timer " + k + " " + ms); }
        @Override public void cancelTimers() { timers.clear(); }
        @Override public void progress(int i, long ms) { }
        @Override public void finished() { finished++; log.add("finished"); }
        long[] timer(AutoplayFlow.Timer k) { for (long[] t : timers) if (t[1] == k.ordinal()) return t; return null; }
    }

    static int[] words(int... w) { return w; }

    @Test public void listenAdvancesOnlyWhenSpeechFinishesPlusAPause() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40, 40), LISTEN, 15, true, r);
        f.start();
        assertEquals(0, r.shown);
        assertNull("no fixed per-story limit while speaking", r.timer(ADVANCE));
        f.onSpeechStarted(f.token());
        assertNull(r.timer(ADVANCE));
        long[] wd = r.timer(WATCHDOG);
        assertTrue("the watchdog is far longer than the speech", wd[2] > 2 * AutoplayFlow.speechEstimateMs(40) + 10_000);
        f.onSpeechDone(f.token());
        long[] adv = r.timer(ADVANCE);
        assertEquals(AutoplayFlow.PAUSE_AFTER_SPEECH_MS, adv[2]);
        assertEquals(0, r.shown);                       // still on story 0 during the pause
        f.onTimer(adv[0], ADVANCE);
        assertEquals(1, r.shown);
    }

    /** The TV bug: a late "finished" from the story that was skipped must not end the new one. */
    @Test public void aStaleFinishedAfterASkipIsIgnored() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40, 40), LISTEN, 15, true, r);
        f.start();
        long first = f.token();
        f.onSpeechStarted(first);
        f.next();                                       // Right: skip to story 1
        long second = f.token();
        assertNotEquals(first, second);
        f.onSpeechDone(first);                          // the old voice reports quiet, late
        assertNull("no advance from a stale finished", r.timer(ADVANCE));
        f.onTimer(first, ADVANCE);                      // and a stale timer does nothing either
        assertEquals(1, r.shown);
        f.onSpeechDone(second);
        assertNotNull(r.timer(ADVANCE));
    }

    @Test public void skippingStopsTheOldSpeechAndStartsExactlyOneNew() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40, 40), LISTEN, 15, true, r);
        f.start();
        int stopsBefore = r.stops;
        f.next();
        assertTrue("the old speech is cut before the new starts", r.stops > stopsBefore);
        assertEquals(2, r.spoken.size());
        assertEquals("speak 1 t2", r.log.get(r.log.lastIndexOf("speak 1 t2")));
        f.prev();
        assertEquals(0, r.shown);
        assertEquals(3, r.spoken.size());
    }

    @Test public void theNextStorysAudioIsFetchedWhileTheCurrentOneIsSaid() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40, 40), LISTEN, 15, true, r);
        f.start();
        assertTrue(r.log.contains("prefetch 1"));
        f.next(); f.next();
        assertTrue("the loop's first story is fetched before it comes round", r.log.contains("prefetch 0"));
    }

    @Test public void pauseSilencesAndResumeSaysTheStoryAgainIgnoringOldCallbacks() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40), LISTEN, 15, true, r);
        f.start();
        long before = f.token();
        f.togglePause();
        assertTrue(f.paused());
        assertTrue(r.timers.isEmpty());
        f.onSpeechDone(before);
        assertNull(r.timer(ADVANCE));
        f.togglePause();
        assertFalse(f.paused());
        assertEquals(0, r.shown);
        assertEquals(2, r.spoken.size());               // said again from the start
        assertNotEquals(before, (long) r.spoken.get(1));
        f.onSpeechDone(before);                         // still stale
        assertNull(r.timer(ADVANCE));
    }

    @Test public void aVoiceThatFailsFallsBackToSilentTimeForThatStory() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40), LISTEN, 15, true, r);
        f.start();
        f.onSpeechFailed(f.token());
        assertEquals(15_000, r.timer(ADVANCE)[2]);
    }

    @Test public void aVoiceThatNeverStartsIsRescuedByTheWatchdog() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40), LISTEN, 15, true, r);
        f.start();
        long[] wd = r.timer(WATCHDOG);
        assertEquals(AutoplayFlow.START_WATCHDOG_MS, wd[2]);
        f.onTimer(wd[0], WATCHDOG);
        assertEquals(1, r.shown);
    }

    @Test public void playIsSilentWithACalmTimeScaledToLength() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 12, 120), PLAY, 15, true, r);
        f.start();
        assertTrue("no speech in PLAY", r.spoken.isEmpty());
        assertEquals(15_000, r.timer(ADVANCE)[2]);
        f.onTimer(f.token(), ADVANCE);
        assertEquals(1, r.shown);
        assertEquals("short stories get less time, never under 6 s", 11_250, r.timer(ADVANCE)[2]);
        f.onTimer(f.token(), ADVANCE);
        assertEquals("long stories get more, at most 1.5 times", 22_500, r.timer(ADVANCE)[2]);
        assertEquals(6_000, AutoplayFlow.silentMs(3, 6_000));
        assertEquals(10_000, AutoplayFlow.silentMs(40, 10_000));
        assertEquals(20_000, AutoplayFlow.silentMs(40, 20_000));
    }

    @Test public void theBriefingEndsAndTheShowLoops() {
        Rec b = new Rec();
        AutoplayFlow brief = new AutoplayFlow(words(40, 40), LISTEN, 15, false, b);
        brief.start();
        brief.onSpeechDone(brief.token()); brief.onTimer(brief.token(), ADVANCE);
        brief.onSpeechDone(brief.token()); brief.onTimer(brief.token(), ADVANCE);
        assertEquals(1, b.finished);
        brief.onTimer(brief.token(), ADVANCE);          // nothing after the end
        assertEquals(1, b.finished);

        Rec s = new Rec();
        AutoplayFlow show = new AutoplayFlow(words(40, 40), PLAY, 10, true, s);
        show.start();
        show.onTimer(show.token(), ADVANCE);
        show.onTimer(show.token(), ADVANCE);
        assertEquals(0, s.shown);
        assertEquals(0, s.finished);
    }

    @Test public void afterStopNothingFires() {
        Rec r = new Rec();
        AutoplayFlow f = new AutoplayFlow(words(40, 40), LISTEN, 15, true, r);
        f.start();
        long t = f.token();
        f.stop();
        f.onSpeechDone(t);
        f.onTimer(t, ADVANCE);
        f.next();
        assertEquals(0, r.shown);
        assertTrue(r.timers.isEmpty());
    }
}
