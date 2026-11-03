package ai.futurespeak.friday.tv.news;

/**
 * What autoplay does next, kept apart from views and audio so every race can be tested.
 *
 * Every story put on screen gets a new token. Speech, timers and their callbacks all carry
 * the token they were started for, and anything carrying an old token is ignored. So a
 * "finished" from a story that was skipped, or a timer from before a pause, can never move
 * the show on. (That was the TV bug: the shared voice reports "quiet" after a stop, late, and
 * the old autoplay took it as the new story finishing.)
 *
 * LISTEN: say the story; when the voice reports it finished, pause {@link #PAUSE_AFTER_SPEECH_MS},
 * then the next story. No fixed time limit cuts speech off; a watchdog only rescues a voice
 * that never starts or stops reporting. While a story is spoken, the next one's audio is fetched.
 * PLAY: silent; each story stays for a calm time scaled to its length ({@link #silentMs}).
 */
final class AutoplayFlow {
    enum Mode { LISTEN, PLAY }
    enum Timer { ADVANCE, WATCHDOG }

    interface Out {
        void show(int index);
        /** Start saying story {@code index}; report {@link #onSpeechStarted}, {@link #onSpeechDone} or {@link #onSpeechFailed} with {@code token}. */
        void speak(long token, int index);
        void stopSpeech();
        /** Get story {@code index}'s audio ready (LISTEN). */
        void prefetch(int index);
        /** Call {@link #onTimer} with {@code token} and {@code kind} after {@code ms}. */
        void startTimer(long token, Timer kind, long ms);
        void cancelTimers();
        /** Run story {@code index}'s progress bar to full over {@code ms}; 0 = fill it now; -1 = hold where it is. */
        void progress(int index, long ms);
        /** The last story is done (a briefing ends here; the regular show loops instead). */
        void finished();
    }

    static final long PAUSE_AFTER_SPEECH_MS = 1_200;
    /** A voice that hasn't started after this long is given up on for this story. */
    static final long START_WATCHDOG_MS = 25_000;
    static final long MIN_SILENT_MS = 6_000;
    static final int[] SILENT_CHOICES_S = {10, 15, 20};

    private final int[] words;
    private final Mode mode;
    private final long silentBaseMs;
    private final boolean loop;
    private final Out out;
    private int index = -1;
    private long token = 0;
    private boolean paused = false, finished = false;

    /**
     * @param words        words per story (headline and summary), for timings
     * @param silentSeconds the chosen PLAY time for a story of ordinary length (10, 15 or 20)
     * @param loop         start again after the last story (false for the briefing)
     */
    AutoplayFlow(int[] words, Mode mode, int silentSeconds, boolean loop, Out out) {
        this.words = words.clone();
        this.mode = mode;
        this.silentBaseMs = silentSeconds * 1000L;
        this.loop = loop;
        this.out = out;
    }

    int index() { return index; }
    long token() { return token; }
    boolean paused() { return paused; }
    Mode mode() { return mode; }

    void start() { if (words.length == 0) { finished = true; out.finished(); } else go(0); }

    /** Right / next. */
    void next() { if (!finished && words.length > 0) go(index + 1 >= words.length ? (loop ? 0 : words.length - 1) : index + 1); }

    /** Left / previous. */
    void prev() { if (!finished && words.length > 0) go(Math.max(0, index - 1)); }

    void togglePause() { setPaused(!paused); }

    void setPaused(boolean p) {
        if (finished || p == paused) return;
        paused = p;
        if (p) {
            out.cancelTimers();
            out.stopSpeech();
            out.progress(index, -1);
        } else {
            token++;            // anything from before the pause is now stale
            begin();            // LISTEN says the story again from the start
        }
    }

    /** Leave autoplay (Back): nothing may fire afterwards. */
    void stop() {
        finished = true;
        token++;
        out.cancelTimers();
        out.stopSpeech();
    }

    void onSpeechStarted(long t) {
        if (t != token || paused || finished) return;
        // Speaking: replace the start watchdog with one sized to the story (it only matters if the voice hangs).
        out.cancelTimers();
        out.startTimer(token, Timer.WATCHDOG, speechWatchdogMs(words[index]));
        out.progress(index, speechEstimateMs(words[index]));
    }

    void onSpeechDone(long t) {
        if (t != token || paused || finished) return;
        out.cancelTimers();
        out.progress(index, 0);
        out.startTimer(token, Timer.ADVANCE, PAUSE_AFTER_SPEECH_MS);
    }

    /** No voice for this story (network, engine): show it silently for its PLAY time instead. */
    void onSpeechFailed(long t) {
        if (t != token || paused || finished) return;
        out.cancelTimers();
        long ms = silentMs(words[index], silentBaseMs);
        out.progress(index, ms);
        out.startTimer(token, Timer.ADVANCE, ms);
    }

    void onTimer(long t, Timer kind) {
        if (t != token || paused || finished) return;
        if (kind == Timer.WATCHDOG) out.stopSpeech();
        advance();
    }

    private void advance() {
        if (index + 1 < words.length) go(index + 1);
        else if (loop) go(0);
        else { finished = true; token++; out.cancelTimers(); out.stopSpeech(); out.progress(index, 0); out.finished(); }
    }

    private void go(int i) {
        out.cancelTimers();
        out.stopSpeech();
        index = i;
        token++;
        out.show(i);
        begin();
    }

    private void begin() {
        if (paused || finished) return;
        if (mode == Mode.LISTEN) {
            out.progress(index, -1);
            out.speak(token, index);
            out.startTimer(token, Timer.WATCHDOG, START_WATCHDOG_MS);
            if (index + 1 < words.length) out.prefetch(index + 1);
            else if (loop && words.length > 1) out.prefetch(0);
        } else {
            long ms = silentMs(words[index], silentBaseMs);
            out.progress(index, ms);
            out.startTimer(token, Timer.ADVANCE, ms);
        }
    }

    /** PLAY time: the chosen time for an ordinary story (about 40 words), shorter or longer with the text, never under 6 s. */
    static long silentMs(int words, long baseMs) {
        double f = Math.max(0.75, Math.min(1.5, words / 40.0));
        return Math.max(MIN_SILENT_MS, Math.round(baseMs * f));
    }

    /** About how long a story takes to say (2.6 words a second, plus a breath). */
    static long speechEstimateMs(int words) { return Math.round(words / 2.6 * 1000) + 800; }

    /** Generous: twice the estimate plus 15 s. Real speech always finishes well inside it. */
    static long speechWatchdogMs(int words) { return 2 * speechEstimateMs(words) + 15_000; }

    static int words(String s) {
        if (s == null) return 0;
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
