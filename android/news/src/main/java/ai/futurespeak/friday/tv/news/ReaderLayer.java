package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ai.futurespeak.friday.tv.reply.Speakable;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Layer;
import ai.futurespeak.friday.tv.ui.Motion;

/**
 * The reader: the story in large type with nothing else on screen. Up/Down scroll, Left/Right
 * move along the action bar, OK presses. "Read it to me" speaks the story paragraph by
 * paragraph in Friday's voice and follows along on screen.
 */
final class ReaderLayer extends Layer {
    interface Actions {
        boolean isSaved(Story s);
        boolean toggleSaved(Story s);
        void askFriday(Story s, Clusterer.Cluster c);
        /** Friday Studio (through the add-on contract): a podcast from this story, made on the TV after the card's Do it. */
        void makePodcast(Story s, Clusterer.Cluster c, Readability.Article a);
        void openTakes(Story current, Clusterer.Cluster c);
        /** Friday's News voice (shared with autoplay), or null when voice is off. */
        Narrator narrator();
        void markRead(Story s);
        Images images();
        NewsRepo repo();
    }

    private final Story story;
    private final Clusterer.Cluster cluster;
    private final Actions act;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final TextView status, readBtn, saveBtn, metaRight;
    private final View progress;
    private final List<View> paraViews = new ArrayList<>();
    private final List<String> spoken = new ArrayList<>();
    private Narrator voice;
    private boolean reading = false;
    /** The paragraph being read and its token: one utterance per paragraph, the next one fetched while this one plays. */
    private int readingAt = -1;
    private long readToken = 0;
    private static long tokens = 1_000_000;   // distinct from autoplay's tokens
    private int words = 0;
    private final int columnPx;

    ReaderLayer(Context c, Host host, Story story, Clusterer.Cluster cluster, Actions act) {
        super(c, host);
        this.story = story;
        this.cluster = cluster;
        this.act = act;
        setBackgroundColor(0xFF0A0E1A);   // plain ground: nothing competes with the words
        act.markRead(story);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        addView(scroll, new LayoutParams(-1, -1));

        // Picture band, fading into the page.
        FrameLayout band = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        band.setBackground(new Backdrop.Art(story.section.accent, story.source, Brand.display(c), c.getResources().getDisplayMetrics().density, story.id.hashCode()));
        band.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(c);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x330A0E1A, 0x660A0E1A, 0xFF0A0E1A}));
        band.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        page.addView(band, new LinearLayout.LayoutParams(-1, Brand.dp(c, 300)));
        act.images().into(img, story.image, 1600, null);

        columnPx = Math.min(c.getResources().getDisplayMetrics().widthPixels - Brand.dp(c, 120), Brand.dp(c, 740));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(columnPx, -2);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        cl.topMargin = -Brand.dp(c, 150);
        page.addView(col, cl);

        TextView kicker = Brand.text(c, story.section.label.toUpperCase(Locale.ROOT) + "   ·   " + story.source.toUpperCase(Locale.ROOT), 13, story.section.accent, Brand.display(c));
        kicker.setLetterSpacing(0.14f);
        col.addView(kicker);
        TextView title = Brand.text(c, story.title, 34, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setLineSpacing(0, 1.1f);
        title.setShadowLayer(Brand.dp(c, 12), 0, 0, 0xCC05060C);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Brand.dp(c, 12);
        col.addView(title, tl);
        TextView meta = Brand.text(c, metaLine(), 15, Brand.DIM, Brand.body(c));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Brand.dp(c, 12);
        col.addView(meta, ml);
        View rule = new View(c);
        rule.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Brand.CYAN, Brand.VIOLET, 0x00FF00FF}));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Brand.dp(c, 160), Brand.dp(c, 2));
        rl.topMargin = Brand.dp(c, 20);
        rl.bottomMargin = Brand.dp(c, 22);
        col.addView(rule, rl);

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);
        status = Brand.text(c, "Fetching the full story from " + story.source + "…", 16, Brand.DIM, Brand.body(c));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Brand.dp(c, 14);
        col.addView(status, sl);
        TextView origin = Brand.text(c, "Original: " + story.link, 13, Brand.FAINT, Brand.mono(c));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = Brand.dp(c, 26);
        ol.bottomMargin = Brand.dp(c, 140);
        col.addView(origin, ol);

        showParagraphs(story.summary.isEmpty() ? new ArrayList<>() : java.util.Collections.singletonList(story.summary), false);
        if (!Motion.reduced(c)) {
            status.animate().alpha(0.35f).setDuration(700).withEndAction(new Runnable() {
                @Override public void run() { if (status.getVisibility() == VISIBLE && status.getTag() == null) status.animate().alpha(status.getAlpha() < 0.5f ? 1f : 0.35f).setDuration(700).withEndAction(this).start(); }
            }).start();
        }

        // Read progress, a thin line along the top.
        progress = new View(c);
        progress.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Brand.CYAN, Brand.VIOLET}));
        progress.setPivotX(0);
        progress.setScaleX(0);
        addView(progress, new LayoutParams(-1, Brand.dp(c, 3), Gravity.TOP));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            int max = Math.max(1, page.getHeight() - scroll.getHeight());
            progress.setScaleX(Math.min(1f, y / (float) max));
        });

        // The action bar, pinned at the bottom.
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Brand.dp(c, 40), Brand.dp(c, 34), Brand.dp(c, 40), Brand.dp(c, 18));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF070A14, 0xFF070A14, 0xF2070A14, 0x00070A14}));
        readBtn = addButton(bar, act.narrator() == null ? "Voice is off" : "▶  Read it to me", v -> toggleReading());
        saveBtn = addButton(bar, "", v -> { act.toggleSaved(story); updateSave(); });
        addButton(bar, "✦  Ask Friday", v -> { stopReading(); act.askFriday(story, cluster); });
        addButton(bar, "🎙  Podcast", v -> { stopReading(); act.makePodcast(story, cluster, article); });
        int takes = cluster == null ? 0 : cluster.otherTakes().size();
        if (takes > 0) addButton(bar, "⇄  " + takes + (takes == 1 ? " other take" : " other takes"), v -> { stopReading(); act.openTakes(story, cluster); });
        metaRight = Brand.text(c, "", 14, Brand.FAINT, Brand.body(c));
        metaRight.setGravity(Gravity.END);
        metaRight.setSingleLine(true);   // with five buttons the bar can leave it little room: one line, never a vertical strip
        metaRight.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(metaRight, new LinearLayout.LayoutParams(0, -2, 1));
        addView(bar, new LayoutParams(-1, -2, Gravity.BOTTOM));
        updateSave();
        if (act.narrator() == null) readBtn.setEnabled(false);

        act.repo().article(story, this::loaded);
    }

    private TextView addButton(LinearLayout bar, String label, OnClickListener l) {
        TextView b = Brand.button(getContext(), label, Brand.CYAN);
        b.setTextSize(17);
        b.setOnClickListener(x -> { lastPressed = x; l.onClick(x); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Brand.dp(getContext(), 14);
        bar.addView(b, lp);
        return b;
    }

    private String metaLine() {
        StringBuilder s = new StringBuilder();
        if (!story.author.isEmpty() && !story.author.equalsIgnoreCase(story.source)) s.append("By ").append(story.author).append("   ·   ");
        SimpleDateFormat f = new SimpleDateFormat("EEE, MMM d  ·  h:mm a", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        s.append(f.format(new Date(story.published)));
        int n = cluster == null ? 1 : cluster.sourceCount();
        if (n > 1) s.append("   ·   ").append(n).append(" outlets covering this");
        return s.toString();
    }

    private Readability.Article article;

    private void loaded(Readability.Article a) {
        if (!isAttachedToWindow()) return;
        article = a;
        status.setTag("done");
        status.animate().cancel();
        status.setAlpha(1f);
        if (a != null && a.paragraphs.size() >= 2) {
            List<String> ps = new ArrayList<>(a.paragraphs);
            showParagraphs(ps, true);
            status.setVisibility(GONE);
        } else {
            status.setText(story.source + " doesn't share the full story with apps, so this is the summary from its feed. "
                    + "The whole story is at the link below.");
            status.setTextColor(Brand.DIM);
        }
        if (words > 0) metaRight.setText(Math.max(1, Math.round(words / 230f)) + " min read  ·  " + Sources.host(story.link).replaceFirst("^www\\.", ""));
        else metaRight.setText(Sources.host(story.link).replaceFirst("^www\\.", ""));
    }

    private void showParagraphs(List<String> ps, boolean full) {
        Context c = getContext();
        body.removeAllViews();
        paraViews.clear();
        spoken.clear();
        words = 0;
        spoken.add(story.title + ". From " + story.source + ".");
        for (String p : ps) {
            boolean head = p.startsWith(Readability.HEADING);
            String t = head ? p.substring(Readability.HEADING.length()) : p;
            TextView tv = Brand.text(c, t, head ? 25 : 22, head ? 0xFFFFFFFF : 0xE0FFFFFF, head ? Brand.bodyBold(c) : Brand.body(c));
            tv.setLineSpacing(0, head ? 1.2f : 1.5f);
            int pad = Brand.dp(c, 18);
            tv.setPadding(pad, Brand.dp(c, head ? 14 : 4), 0, Brand.dp(c, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = -pad;
            lp.bottomMargin = Brand.dp(c, head ? 8 : 20);
            body.addView(tv, lp);
            paraViews.add(tv);
            spoken.add(t);
            words += t.split("\\s+").length;
        }
        if (!full && ps.size() == 1) ((TextView) paraViews.get(0)).setTextSize(24);
    }

    private void updateSave() {
        saveBtn.setText(act.isSaved(story) ? "★  Saved" : "☆  Save");
    }

    // ------------------------------------------------------------ read aloud -----

    private void toggleReading() {
        if (reading) { stopReading(); return; }
        voice = act.narrator();
        if (voice == null) return;
        voice.setListener(new Narrator.Listener() {
            @Override public void started(long t) { if (reading && t == readToken) highlight(readingAt - 1); }
            @Override public void done(long t) { if (reading && t == readToken) readFrom(readingAt + 1); }
            @Override public void failed(long t, String why) { if (reading && t == readToken) readFrom(readingAt + 1); }
        });
        reading = true;
        readBtn.setText("■  Stop reading");
        readFrom(0);
    }

    /** Says paragraph {@code i} (0 is the headline); the one after it is fetched meanwhile. */
    private void readFrom(int i) {
        while (i < spoken.size() && Speakable.clean(spoken.get(i)).isEmpty()) i++;
        if (i >= spoken.size()) { stopReading(); return; }
        readingAt = i;
        readToken = ++tokens;
        voice.say(readToken, story.id + "#" + i, Speakable.clean(spoken.get(i)));
        if (i + 1 < spoken.size()) voice.prefetch(story.id + "#" + (i + 1), Speakable.clean(spoken.get(i + 1)));
    }

    private void stopReading() {
        if (!reading) return;
        reading = false;
        readToken = 0;
        if (voice != null) voice.stop();
        highlight(-1);
        readBtn.setText("▶  Read it to me");
    }

    private void highlight(int p) {
        for (int i = 0; i < paraViews.size(); i++) {
            TextView tv = (TextView) paraViews.get(i);
            boolean on = i == p;
            tv.setBackground(on ? bar() : null);
            if (on) {
                int y = tv.getTop() + ((View) body.getParent()).getTop() + body.getTop() - scroll.getHeight() / 4;
                if (Motion.reduced(getContext())) scroll.scrollTo(0, Math.max(0, y)); else scroll.smoothScrollTo(0, Math.max(0, y));
            }
        }
    }

    private GradientDrawable bar() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x2600D4FF, 0x0000D4FF});
        g.setCornerRadius(Brand.dp(getContext(), 6));
        return g;
    }

    // ------------------------------------------------------------------ keys -----

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int k = e.getKeyCode();
        int step = scroll.getHeight() / 3;
        if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) { scrollBy(k == KeyEvent.KEYCODE_PAGE_DOWN ? step * 2 : step); return true; }
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_PAGE_UP) { scrollBy(-(k == KeyEvent.KEYCODE_PAGE_UP ? step * 2 : step)); return true; }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY) { if (e.getRepeatCount() == 0) toggleReading(); return true; }   // toggles once per press
        return false;
    }

    private void scrollBy(int dy) {
        if (Motion.reduced(getContext())) scroll.scrollBy(0, dy); else scroll.smoothScrollBy(0, dy);
    }

    @Override public void back() { stopReading(); super.back(); }
    @Override public void closed() { stopReading(); }
    /** On return from a screen this one opened, focus goes back to the button that opened it. */
    @Override public void focusFirst() { (lastPressed != null ? lastPressed : readBtn.isEnabled() ? readBtn : saveBtn).requestFocus(); }
    private View lastPressed;
    @Override public boolean blocksTalk() { return false; }
}
