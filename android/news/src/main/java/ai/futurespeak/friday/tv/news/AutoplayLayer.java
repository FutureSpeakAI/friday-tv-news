package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import ai.futurespeak.friday.tv.reply.Speakable;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Layer;
import ai.futurespeak.friday.tv.ui.Motion;

/**
 * Lean-back news, one story at a time, full screen, its picture drifting slowly behind the
 * headline and summary. Two ways to watch:
 *
 *  - LISTEN: Friday reads each story; the next one comes after she finishes and a short pause.
 *  - PLAY: silent; each story stays for a calm time scaled to its length (10/15/20 s for an
 *    ordinary story, in News settings), with the progress bar showing it.
 *
 * All timing decisions are {@link AutoplayFlow}'s (tested); this class draws and plays. Left/Right
 * skip, OK pauses, Down opens the story in the reader, Back leaves. A snapshot of the stories is
 * taken at the start, so a background refresh never changes the show under the viewer.
 */
final class AutoplayLayer extends Layer implements AutoplayFlow.Out {
    interface Actions extends ReaderLayer.Actions {
        void openReader(Story s, Clusterer.Cluster c);
        long now();
    }

    private static final long INTRO_TOKEN = -1, OUTRO_TOKEN = -2;

    private final List<Clusterer.Cluster> queue;
    private final Actions act;
    private final String intro, introShown, introNote, outro;
    private final Supplier<String> clock;
    private final Narrator narrator;
    private final AutoplayFlow flow;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Object timerTag = new Object();
    private boolean heldForReader = false, started = false;
    private final Narrator.Listener listener;

    private final ImageView[] pics = new ImageView[2];
    private final FrameLayout[] picFrames = new FrameLayout[2];
    private int front = 0;
    private int shownIndex = -1;
    private final TextView kicker, meta, title, summary, counter, clockView, pauseChip, hint, takes;
    private final LinearLayout segments;
    /** Playing one section: each story is shown as that section's outlet told it. */
    private Section section;

    /**
     * @param mode   LISTEN or PLAY (LISTEN without a voice on this TV plays silently)
     * @param intro  spoken before the first story (the briefing), with {@code introShown}/{@code introNote} on screen; null for none
     * @param outro  spoken after the last story; when set, the show ends there instead of looping
     */
    AutoplayLayer(Context c, Host host, List<Clusterer.Cluster> queue, AutoplayFlow.Mode mode, int playSeconds, Section section,
                  String intro, String introShown, String introNote, String outro, Supplier<String> clock, Actions act) {
        super(c, host);
        this.queue = new ArrayList<>(queue);
        this.act = act;
        this.section = section;
        this.intro = intro;
        this.introShown = introShown;
        this.introNote = introNote;
        this.outro = outro;
        this.clock = clock;
        this.narrator = mode == AutoplayFlow.Mode.LISTEN ? act.narrator() : null;
        AutoplayFlow.Mode m = narrator == null ? AutoplayFlow.Mode.PLAY : mode;
        int[] words = new int[this.queue.size()];
        for (int i = 0; i < words.length; i++) { Story s = lead(this.queue.get(i)); words[i] = AutoplayFlow.words(s.title) + AutoplayFlow.words(s.summary); }
        flow = new AutoplayFlow(words, m, playSeconds, outro == null, this);
        listener = new Narrator.Listener() {
            @Override public void started(long t) { NewsLog.i("autoplay: speech started t" + t); if (t > 0) flow.onSpeechStarted(t); }
            @Override public void done(long t) {
                NewsLog.i("autoplay: speech done t" + t);
                if (t == INTRO_TOKEN) h.postDelayed(AutoplayLayer.this::startShow, AutoplayFlow.PAUSE_AFTER_SPEECH_MS);
                else if (t == OUTRO_TOKEN) h.postDelayed(() -> host.pop(AutoplayLayer.this), 800);
                else flow.onSpeechDone(t);
            }
            @Override public void failed(long t, String why) {
                NewsLog.i("autoplay: speech failed t" + t + " (" + why + ")");
                if (t == INTRO_TOKEN) h.postDelayed(AutoplayLayer.this::startShow, 3500);
                else if (t == OUTRO_TOKEN) h.postDelayed(() -> host.pop(AutoplayLayer.this), 3500);
                else flow.onSpeechFailed(t);
            }
        };
        if (narrator != null) narrator.setListener(listener);
        NewsLog.i("autoplay: start " + m + " with " + words.length + " stories" + (mode != m ? " (no voice on this TV: playing silently)" : ""));

        float density = c.getResources().getDisplayMetrics().density;
        setBackground(new Backdrop(density, 9, 70));
        for (int i = 0; i < 2; i++) {
            picFrames[i] = new FrameLayout(c);
            pics[i] = new ImageView(c);
            pics[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            picFrames[i].addView(pics[i], new FrameLayout.LayoutParams(-1, -1));
            picFrames[i].setAlpha(0f);
            addView(picFrames[i], new LayoutParams(-1, -1));
        }
        View side = new View(c);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20A0E1A, 0xCC0A0E1A, 0x330A0E1A, 0x000A0E1A}));
        addView(side, new LayoutParams(-1, -1));
        View low = new View(c);
        low.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF05060C, 0x9905060C, 0x0005060C}));
        addView(low, new LayoutParams(-1, (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f), Gravity.BOTTOM));
        View top = new View(c);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB305060C, 0x0005060C}));
        addView(top, new LayoutParams(-1, Brand.dp(c, 110), Gravity.TOP));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Brand.dp(c, 48), Brand.dp(c, 26), Brand.dp(c, 48), 0);
        head.addView(NewsActivity.logo(c, 14, 20));
        counter = Brand.text(c, "", 13, Brand.DIM, Brand.display(c));
        counter.setLetterSpacing(0.14f);
        counter.setPadding(Brand.dp(c, 22), Brand.dp(c, 8), 0, 0);
        head.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        clockView = Brand.text(c, "", 26, 0xEEFFFFFF, Brand.display(c));
        clockView.setLetterSpacing(0.06f);
        head.addView(clockView);
        addView(head, new LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Brand.dp(c, 56), 0, Brand.dp(c, 56), Brand.dp(c, 64));
        kicker = Brand.text(c, "", 13, Brand.CYAN, Brand.display(c));
        kicker.setLetterSpacing(0.16f);
        kicker.setPadding(Brand.dp(c, 12), Brand.dp(c, 6), Brand.dp(c, 12), Brand.dp(c, 6));
        text.addView(kicker, new LinearLayout.LayoutParams(-2, -2));
        title = Brand.text(c, "", 36, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setLineSpacing(0, 1.06f);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setShadowLayer(Brand.dp(c, 14), 0, Brand.dp(c, 2), 0xCC000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Brand.dp(c, 16);
        text.addView(title, tl);
        summary = Brand.text(c, "", 19, 0xE6FFFFFF, Brand.body(c));
        summary.setLineSpacing(0, 1.3f);
        summary.setMaxLines(3);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Brand.dp(c, 14);
        text.addView(summary, sl);
        meta = Brand.text(c, "", 16, 0xCCFFFFFF, Brand.bodyBold(c));
        meta.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Brand.dp(c, 18);
        text.addView(meta, ml);
        takes = Brand.text(c, "", 15, 0xFFB9A8FF, Brand.body(c));
        takes.setMaxLines(2);
        takes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, -2);
        kl.topMargin = Brand.dp(c, 6);
        text.addView(takes, kl);
        int w = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.66f);
        addView(text, new LayoutParams(w, -2, Gravity.BOTTOM | Gravity.START));

        segments = new LinearLayout(c);
        segments.setPadding(Brand.dp(c, 56), 0, Brand.dp(c, 56), Brand.dp(c, 30));
        addView(segments, new LayoutParams(-1, -2, Gravity.BOTTOM));
        hint = Brand.text(c, "◀ ▶  skip     OK  pause     ▼  read the story     BACK  stop", 13, Brand.FAINT, Brand.body(c));
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hl.setMargins(0, 0, Brand.dp(c, 56), Brand.dp(c, 42));
        addView(hint, hl);

        pauseChip = Brand.text(c, "❚❚  PAUSED", 16, 0xFFFFFFFF, Brand.display(c));
        pauseChip.setLetterSpacing(0.16f);
        pauseChip.setPadding(Brand.dp(c, 22), Brand.dp(c, 12), Brand.dp(c, 22), Brand.dp(c, 12));
        pauseChip.setBackground(Brand.pill(c, 0xCC0A0E1A, Brand.CYAN));
        pauseChip.setVisibility(GONE);
        addView(pauseChip, new LayoutParams(-2, -2, Gravity.CENTER));

        buildSegments();
        setFocusable(true);
        setFocusableInTouchMode(true);
        post(() -> {
            if (intro != null) showLine(INTRO_TOKEN, intro, introShown, introNote, AutoplayLayer.this::startShow);
            else startShow();
        });
        tickClock();
    }

    private Story lead(Clusterer.Cluster c) { return c.leadFor(section); }

    private void startShow() {
        if (started || !isAttachedToWindow()) return;
        started = true;
        flow.start();
    }

    private final Runnable clockTick = this::tickClock;
    private void tickClock() {
        clockView.setText(clock.get());
        removeCallbacks(clockTick);
        postDelayed(clockTick, 20_000);
    }

    private void buildSegments() {
        segments.removeAllViews();
        int n = Math.min(queue.size(), 30);
        for (int i = 0; i < n; i++) {
            FrameLayout seg = new FrameLayout(getContext());
            seg.setBackground(Brand.pill(getContext(), 0x33FFFFFF, 0));
            View fill = new View(getContext());
            fill.setBackground(Brand.pill(getContext(), Brand.CYAN, 0));
            fill.setPivotX(0);
            fill.setScaleX(0);
            seg.addView(fill, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Brand.dp(getContext(), 4), 1);
            lp.rightMargin = Brand.dp(getContext(), 6);
            segments.addView(seg, lp);
        }
    }

    private View fill(int i) {
        if (i < 0 || i >= segments.getChildCount()) return null;
        return ((FrameLayout) segments.getChildAt(i)).getChildAt(0);
    }

    private String spoken(Story s) {
        return Speakable.clean("From " + s.source + ". " + s.title + (s.title.endsWith(".") || s.title.endsWith("?") ? " " : ". ") + s.summary);
    }

    // ------------------------------------------------------ AutoplayFlow.Out -----

    @Override public void show(int i) {
        shownIndex = i;
        Clusterer.Cluster c = queue.get(i);
        Story s = lead(c);
        act.markRead(s);
        NewsLog.i("autoplay: show " + (i + 1) + "/" + queue.size() + " " + s.sourceId + " " + s.id);
        Context ctx = getContext();
        for (int k = 0; k < segments.getChildCount(); k++) {
            View f = fill(k);
            f.animate().cancel();
            f.setScaleX(k < i ? 1f : 0f);
        }
        String mode = flow.mode() == AutoplayFlow.Mode.LISTEN ? "LISTEN" : "PLAY";
        counter.setText(String.format(Locale.US, "%s   ·   %d / %d   ·   %s", mode, i + 1, queue.size(), s.section.label.toUpperCase(Locale.ROOT)));
        boolean breaking = Desk.breaking(c, act.now());
        kicker.setText(breaking ? "BREAKING" : s.section.label.toUpperCase(Locale.ROOT));
        kicker.setTextColor(breaking ? 0xFFFFFFFF : s.section.accent);
        kicker.setBackground(Brand.pill(ctx, breaking ? 0xE6D4007A : 0x990A0E1A, breaking ? 0 : s.section.accent));
        title.setText(s.title);
        summary.setText(s.summary);
        summary.setMaxLines(3);
        summary.setVisibility(s.summary.isEmpty() ? GONE : VISIBLE);
        int n = c.sourceCount();
        meta.setText(s.source + "   ·   " + RefreshPlan.age(c.newest(), act.now()) + " ago" + (n > 1 ? "   ·   " + n + " outlets" : ""));
        List<Story> others = c.otherTakes();
        if (others.isEmpty()) takes.setVisibility(GONE);
        else {
            StringBuilder t = new StringBuilder("Also: ");
            for (int k = 0; k < Math.min(3, others.size()); k++) t.append(k > 0 ? "  ·  " : "").append(others.get(k).source);
            takes.setText(t);
            takes.setVisibility(VISIBLE);
        }
        boolean reduced = Motion.reduced(ctx);
        if (!reduced) {
            for (View v : new View[]{kicker, title, summary, meta, takes}) {
                v.setAlpha(0f);
                v.setTranslationY(Brand.dp(ctx, 14));
                v.animate().alpha(1f).translationY(0).setStartDelay(v == kicker ? 120 : v == title ? 180 : 260).setDuration(420).start();
            }
        }
        showPicture(s, reduced, i);
        Story after = lead(queue.get((i + 1) % queue.size()));
        if (after.image != null) act.images().prefetch(after.image, 1920);
        else act.repo().findImage(after, () -> act.images().prefetch(after.image, 1920));
    }

    @Override public void speak(long token, int i) {
        if (narrator == null) return;
        Story s = lead(queue.get(i));
        NewsLog.i("autoplay: say t" + token + " " + s.id);
        narrator.say(token, s.id, spoken(s));
    }

    @Override public void stopSpeech() { if (narrator != null) narrator.stop(); }

    @Override public void prefetch(int i) {
        if (narrator == null || i < 0 || i >= queue.size()) return;
        Story s = lead(queue.get(i));
        narrator.prefetch(s.id, spoken(s));
    }

    @Override public void startTimer(long token, AutoplayFlow.Timer kind, long ms) {
        h.postAtTime(() -> { NewsLog.i("autoplay: timer " + kind + " t" + token); flow.onTimer(token, kind); }, timerTag, SystemClock.uptimeMillis() + ms);
    }

    @Override public void cancelTimers() { h.removeCallbacksAndMessages(timerTag); }

    @Override public void progress(int i, long ms) {
        View f = fill(i);
        if (f == null) return;
        f.animate().cancel();
        if (ms < 0) return;
        if (ms == 0) { f.setScaleX(1f); return; }
        f.animate().scaleX(1f).setDuration(ms).setInterpolator(new LinearInterpolator()).start();
    }

    @Override public void finished() {
        NewsLog.i("autoplay: finished");
        if (outro != null) showLine(OUTRO_TOKEN, outro, outro, null, () -> host.pop(this));
        else host.pop(this);
    }

    // -------------------------------------------------------------- pictures -----

    private void showPicture(Story s, boolean reduced, int i) {
        int back = front;
        front = 1 - front;
        FrameLayout f = picFrames[front];
        ImageView v = pics[front];
        f.setBackground(new Backdrop.Art(s.section.accent, s.source, Brand.display(getContext()), getResources().getDisplayMetrics().density, s.id.hashCode()));
        v.animate().cancel();
        v.setScaleX(1f); v.setScaleY(1f); v.setTranslationX(0);
        Runnable reveal = () -> {
            if (shownIndex != i) return;
            if (reduced) { f.setAlpha(1f); picFrames[back].setAlpha(0f); }
            else {
                f.animate().alpha(1f).setDuration(700).start();
                picFrames[back].animate().alpha(0f).setDuration(700).start();
                // A slow push in, alternating direction: one bitmap, hardware-scaled.
                v.setLayerType(LAYER_TYPE_HARDWARE, null);
                v.animate().scaleX(1.08f).scaleY(1.08f).translationX((i % 2 == 0 ? -1 : 1) * Brand.dp(getContext(), 16))
                        .setDuration(30_000).setInterpolator(new LinearInterpolator()).start();
            }
        };
        if (s.image == null) {
            v.setImageDrawable(null);
            reveal.run();
            act.repo().findImage(s, () -> { if (shownIndex == i) act.images().into(v, s.image, 1920, null); });
        } else {
            act.images().into(v, s.image, 1920, ok -> reveal.run());
        }
    }

    // ------------------------------------------------------- intro and outro -----

    /** The briefing's spoken lines (with the stories as an agenda under the intro). */
    private void showLine(long token, String text, String shown, String note, Runnable silentNext) {
        title.setText(shown == null ? text : shown);
        meta.setText(note == null ? "" : note);
        takes.setVisibility(GONE);
        kicker.setText(token == INTRO_TOKEN ? "TODAY'S BRIEFING" : "FRIDAY NEWS");
        kicker.setTextColor(Brand.CYAN);
        kicker.setBackground(Brand.pill(getContext(), 0x990A0E1A, Brand.CYAN));
        if (token == INTRO_TOKEN) {
            StringBuilder agenda = new StringBuilder();
            for (int i = 0; i < Math.min(5, queue.size()); i++) agenda.append(i > 0 ? "\n" : "").append(i + 1).append("   ").append(Html.clip(lead(queue.get(i)).title, 52));
            summary.setText(agenda);
            summary.setMaxLines(5);
            summary.setVisibility(VISIBLE);
        } else summary.setVisibility(GONE);
        if (narrator == null) { h.postDelayed(silentNext, 4500); return; }
        narrator.say(token, "line" + token, Speakable.clean(text));
    }

    // ------------------------------------------------------------------ keys -----

    /** Leaving the screen (Home, another app): pause, and stay paused on return. */
    void hold() { if (started) flow.setPaused(true); pauseChip.setVisibility(started && flow.paused() ? VISIBLE : GONE); }

    @Override public boolean key(KeyEvent e) {
        // One skip, pause or open per press: a held arrow's repeats don't race through the stories.
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        if (!started) return true;   // the briefing's intro: Back still leaves (handled by the screen)
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                NewsLog.i("autoplay: skip next");
                flow.setPaused(false); pauseChip.setVisibility(GONE);
                flow.next(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND:
                NewsLog.i("autoplay: skip back");
                flow.setPaused(false); pauseChip.setVisibility(GONE);
                flow.prev(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_SPACE:
                flow.togglePause();
                NewsLog.i("autoplay: " + (flow.paused() ? "paused" : "resumed"));
                pauseChip.setVisibility(flow.paused() ? VISIBLE : GONE);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (flow.index() >= 0) {
                    flow.setPaused(true);
                    heldForReader = true;
                    Clusterer.Cluster c = queue.get(flow.index());
                    act.openReader(lead(c), c);
                }
                return true;
            default:
                return true;
        }
    }

    /** Back from the reader (which shares the voice): take the voice back and carry on where it left off. */
    @Override public void focusFirst() {
        requestFocus();
        if (narrator != null) narrator.setListener(listener);
        if (heldForReader) { heldForReader = false; flow.setPaused(false); pauseChip.setVisibility(GONE); }
    }

    @Override public void closed() {
        flow.stop();
        h.removeCallbacksAndMessages(null);
        removeCallbacks(clockTick);
        if (narrator != null) narrator.stop();
        for (ImageView v : pics) v.animate().cancel();
        NewsLog.i("autoplay: closed");
    }

    @Override public boolean blocksTalk() { return false; }
}
