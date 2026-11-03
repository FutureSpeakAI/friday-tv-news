package ai.futurespeak.friday.tv.news;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.futurespeak.friday.tv.addon.FridayTv;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Layer;
import ai.futurespeak.friday.tv.ui.Motion;

/**
 * Friday News: U.S., World, Business, Science and Technology from the outlets
 * in {@link Sources}, with autoplay, a reader, story clustering, a ticker and the local weather.
 * A Friday TV add-on (contract v1): Friday opens it from its rail or for a spoken request
 * ({@link NewsAddonProvider}); everything else it shares comes through FridayTv.
 */
public final class NewsActivity extends Activity implements Layer.Host, AutoplayLayer.Actions {
    public static final String EXTRA_SECTION = "news_section";
    public static final String EXTRA_MODE = "news_mode";
    public static final String MODE_BROWSE = "browse", MODE_PLAY = "play", MODE_LISTEN = "listen", MODE_BRIEFING = "briefing";
    /** Opens one story in the reader (from Friday, with a story id). */
    public static final String EXTRA_STORY_ID = "news_story_id";

    /** Times are shown in the TV's own time zone. */
    static TimeZone zone() { return TimeZone.getDefault(); }
    private static final int MAX_GRID = 45;
    private static final long WEATHER_MS = 30 * 60_000L;

    /** Opens News for a spoken request. Returns false if the words weren't a news request. */
    public static boolean openFor(Context c, String utterance) {
        NewsIntent i = NewsIntent.match(utterance);
        if (i == null) return false;
        Intent x = new Intent(c, NewsActivity.class);
        if (i.section != null) x.putExtra(EXTRA_SECTION, i.section.name());
        x.putExtra(EXTRA_MODE, i.kind == NewsIntent.Kind.BRIEFING ? MODE_BRIEFING : i.listen ? MODE_LISTEN : i.play ? MODE_PLAY : MODE_BROWSE);
        c.startActivity(x);
        return true;
    }

    public static void open(Context c) { c.startActivity(new Intent(c, NewsActivity.class)); }

    // ------------------------------------------------------------------ state -----

    private enum TabKind { TOP, SECTION, SAVED, PLAY, LISTEN, SETTINGS }
    private static final class Tab {
        final TabKind kind; final Section section; final String label;
        Tab(TabKind k, Section s, String l) { kind = k; section = s; label = l; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "news-desk"); t.setDaemon(true); return t; });
    private NewsSettings prefs;
    private NewsStore store;
    private NewsRepo repo;
    private Images images;
    private RefreshPlan plan;
    private List<Story> stories = new ArrayList<>();
    private List<Clusterer.Cluster> clusters = new ArrayList<>();
    private boolean loaded = false, kids = false, offline = false;
    private int lastOk = 0;
    private Weather weather;
    private long weatherAt = 0;
    private Tab tab;
    private String pendingMode;
    private Section pendingSection;

    private FrameLayout root;
    private LinearLayout tabRow, content;
    private FocusScroll contentScroll;
    private TextView clockView, dateView, stampView, weatherView, tickerLabel, tickerText, noticeView;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<StoryCard> cards = new ArrayList<>();
    private final List<Layer> layers = new ArrayList<>();

    private Narrator narrator;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        createdAt = android.os.SystemClock.uptimeMillis();
        NewsLog.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        prefs = new NewsSettings(this);
        store = new NewsStore(new java.io.File(getFilesDir(), "news"));
        repo = new NewsRepo(store);
        images = new Images(this);
        buildUi();
        readIntent(getIntent());
        // The last edition first, at once: the cache plus the grouping saved with it (no grouping
        // work on the way in). The refresh, if one is due, comes after, in the background.
        work.execute(() -> {
            NewsStore.Snapshot snap = store.loadCache();
            long now = System.currentTimeMillis();
            List<List<String>> groups = snap.stories.isEmpty() ? null : store.loadGroups(snap.updated);
            List<Clusterer.Cluster> cs = groups != null ? Clusterer.rebuild(snap.stories, groups, now) : Clusterer.cluster(snap.stories, now);
            if (groups == null && !snap.stories.isEmpty()) store.saveGroups(Clusterer.groups(cs), snap.updated);
            NewsLog.i("open: cached edition of " + snap.stories.size() + " stories, " + (groups != null ? "saved grouping" : "grouped now"));
            main.post(() -> {
                plan = new RefreshPlan(snap.updated);
                if (!snap.stories.isEmpty()) showEdition(snap.stories, cs, false);
                else showEmpty(true);
                scheduleRefresh();
                work.execute(() -> store.prune(System.currentTimeMillis()));
            });
        });
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        readIntent(i);
        if (loaded) runPending();
    }

    private void readIntent(Intent i) {
        if (i == null) return;
        pendingMode = i.getStringExtra(EXTRA_MODE);
        pendingSection = Section.parse(i.getStringExtra(EXTRA_SECTION));
        // Opened by Friday for a spoken request (the add-on contract): read the request the same way.
        String said = i.getStringExtra(ai.futurespeak.friday.tv.addon.Contract.EXTRA_UTTERANCE);
        NewsIntent ni = pendingMode == null && said != null ? NewsIntent.match(said) : null;
        if (ni != null) {
            pendingSection = ni.section;
            pendingMode = ni.kind == NewsIntent.Kind.BRIEFING ? MODE_BRIEFING : ni.listen ? MODE_LISTEN : ni.play ? MODE_PLAY : MODE_BROWSE;
        }
        if (pendingSection != null) selectTab(tabFor(pendingSection), false);
        pendingStory = i.getStringExtra(EXTRA_STORY_ID);
    }
    private String pendingStory;

    private void runPending() {
        String id = pendingStory;
        pendingStory = null;
        if (id != null) {
            Story s = null;
            for (Story x : stories) if (x.id.equals(id)) s = x;
            if (s == null) for (Story x : store.saved()) if (x.id.equals(id)) s = x;
            if (s != null) {
                for (Layer l : new ArrayList<>(layers)) pop(l);
                openReader(s, clusterOf(s));
                return;
            }
        }
        String mode = pendingMode;
        pendingMode = null;
        if (mode == null || clusters.isEmpty()) return;
        for (Layer l : new ArrayList<>(layers)) pop(l);
        if (MODE_BRIEFING.equals(mode)) startBriefing();
        else if (MODE_PLAY.equals(mode)) startAutoplay(AutoplayFlow.Mode.PLAY);
        else if (MODE_LISTEN.equals(mode)) startAutoplay(AutoplayFlow.Mode.LISTEN);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onResume() {
        super.onResume();
        // Edge to edge (Google TV shows no bars; this keeps phones and emulators the same).
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        tick.run();
        if (plan != null) scheduleRefresh();
        if (System.currentTimeMillis() - weatherAt > WEATHER_MS) loadWeather();
        work.execute(() -> {
            // Kids mode and the place come from Friday. With Friday installed but not answering (this
            // add-on not enabled yet), News fails closed into kids mode; on a TV without Friday it runs on its own.
            FridayTv.State st = FridayTv.state(this);
            boolean k = st != null ? st.kids : FridayTv.coreVersion(this) > 0;
            NewsPlace.from(st);
            boolean kk = k;
            main.post(() -> { if (kk != kids) { kids = kk; if (loaded) render(); } if (weather == null) loadWeather(); });
        });
    }

    @Override protected void onPause() {
        main.removeCallbacks(tick);
        main.removeCallbacks(refreshTask);
        if (narrator != null) narrator.stop();
        for (Layer l : layers) if (l instanceof AutoplayLayer) ((AutoplayLayer) l).hold();
        super.onPause();
    }

    @Override protected void onDestroy() {
        for (Layer l : new ArrayList<>(layers)) pop(l);
        if (narrator != null) narrator.release();
        repo.release();
        images.release();
        work.shutdownNow();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) images.trimMemory();
    }

    // -------------------------------------------------------------- refreshing -----

    private final Runnable refreshTask = this::refreshNow;

    private void scheduleRefresh() {
        main.removeCallbacks(refreshTask);
        long d = plan.delay(System.currentTimeMillis());
        main.postDelayed(refreshTask, d);
    }

    private void refreshNow() {
        if (repo.busy() || plan == null) return;
        long now = System.currentTimeMillis();
        plan.attempted(now);
        updateStamp();
        repo.refresh(stories, u -> {
            if (u.ok > 0) { plan.succeeded(u.at); offline = false; lastOk = u.ok; apply(u.stories, u.at); }
            else { plan.failed(u.at); offline = true; updateStamp(); if (!loaded) showEmpty(false); }
            scheduleRefresh();
        });
    }

    private void loadWeather() {
        weatherAt = System.currentTimeMillis();
        repo.weather(w -> { if (w != null) { weather = w; updateWeather(); } else weatherAt = 0; });
    }

    /** New stories: cluster them off the main thread, then redraw. */
    /** A fresh edition from the network: group it off the main thread, save the grouping, then show it. */
    private void apply(List<Story> fresh, long updated) {
        List<Story> copy = new ArrayList<>(fresh);
        work.execute(() -> {
            List<Clusterer.Cluster> cs = Clusterer.cluster(copy, System.currentTimeMillis());
            store.saveGroups(Clusterer.groups(cs), updated);
            main.post(() -> showEdition(copy, cs, true));
        });
    }

    private long lastKeyAt = 0;
    private Runnable pendingEdition;
    /** A fresh edition waits while the remote is in use, so the grid never changes under a press. */
    static final long QUIET_BEFORE_SWAP_MS = 2_500;

    /** How long a fresh edition should wait, given how long the remote has been quiet (0 = show it now). */
    static long swapWait(long quietFor) { return quietFor >= QUIET_BEFORE_SWAP_MS ? 0 : QUIET_BEFORE_SWAP_MS - quietFor + 50; }

    /**
     * Puts an edition on screen. The first one simply appears. A later one (a background
     * refresh) waits until the remote has been quiet for {@link #QUIET_BEFORE_SWAP_MS}, keeps the
     * focused story and the scroll position, and fades in only the stories that are new.
     */
    private void showEdition(List<Story> fresh, List<Clusterer.Cluster> cs, boolean fromNetwork) {
        boolean first = !loaded;
        long wait = first ? 0 : swapWait(android.os.SystemClock.uptimeMillis() - lastKeyAt);
        if (wait > 0) {
            if (pendingEdition != null) main.removeCallbacks(pendingEdition);   // only the newest edition waits
            pendingEdition = () -> { pendingEdition = null; showEdition(fresh, cs, fromNetwork); };
            main.postDelayed(pendingEdition, wait);
            return;
        }
        java.util.Set<String> before = new java.util.HashSet<>();
        for (StoryCard c : cards) if (c.story != null) before.add(c.story.id);
        View f = getCurrentFocus();
        String focusedId = f instanceof StoryCard && ((StoryCard) f).story != null ? ((StoryCard) f).story.id : null;
        boolean tabFocused = f != null && f.getParent() == tabRow;
        int scrollY = contentScroll.getScrollY();
        stories = fresh;
        clusters = cs;
        loaded = true;
        render();
        updateStamp();
        timing(first ? (fromNetwork ? "first content (network, no cache)" : "cached edition shown") : "fresh edition shown", fresh.size());
        if (first) {
            if (!cards.isEmpty()) cards.get(0).requestFocus();
            runPending();
            if (fromNetwork) showNotice("Today's news, from " + lastOk + " outlets");
            return;
        }
        contentScroll.post(() -> contentScroll.scrollTo(0, scrollY));
        if (focusedId != null) refocus(focusedId);
        else if (tabFocused) tabViews.get(tabs.indexOf(tab)).requestFocus();
        int added = 0;
        if (!Motion.reduced(this)) {
            for (StoryCard c : cards) {
                if (c.story == null || before.contains(c.story.id)) continue;
                added++;
                c.setAlpha(0f);
                c.animate().alpha(1f).setDuration(450).setStartDelay(80).start();
            }
        }
        NewsLog.i("refresh: fresh edition shown, " + added + " new on screen");
    }

    private long createdAt;

    /** Logs how long after opening News a screen of stories was first drawn (adb logcat -s FridayNews). */
    private void timing(String what, int n) {
        android.view.Choreographer.getInstance().postFrameCallback(t -> android.util.Log.i("FridayNews",
                "timing: " + what + " " + (android.os.SystemClock.uptimeMillis() - createdAt) + " ms after open (" + n + " stories)"));
    }

    private void refocus(String id) {
        for (StoryCard c : cards) if (c.story.id.equals(id)) { c.requestFocus(); return; }
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    // --------------------------------------------------------------------- UI -----

    static LinearLayout logo(Context c, float small, float big) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView mark = Brand.wordmark(c, small);
        mark.setSingleLine(true);
        l.addView(mark, new LinearLayout.LayoutParams(-2, -2));   // wrap: a vertical layout's default would match "NEWS"'s width
        TextView news = Brand.text(c, "NEWS", big, Brand.CYAN, Brand.display(c));
        news.setLetterSpacing(0.32f);
        news.getPaint().setShader(new LinearGradient(0, 0, big * 6 * c.getResources().getDisplayMetrics().scaledDensity, 0,
                new int[]{Brand.CYAN, Brand.VIOLET, Brand.MAGENTA}, null, Shader.TileMode.CLAMP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Brand.dp(c, 3);
        l.addView(news, lp);
        return l;
    }

    private int dp(float v) { return Brand.dp(this, v); }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(new Backdrop(getResources().getDisplayMetrics().density, 4, 64));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Header: logo, date and update stamp; weather and clock on the right.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(40), dp(20), dp(40), 0);
        head.addView(logo(this, 13, 24));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setPadding(dp(30), dp(4), 0, 0);
        dateView = Brand.text(this, "", 13, Brand.DIM, Brand.display(this));
        dateView.setLetterSpacing(0.14f);
        when.addView(dateView);
        stampView = Brand.text(this, "", 14, Brand.FAINT, Brand.body(this));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(6);
        when.addView(stampView, sl);
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clockView = Brand.text(this, "", 30, 0xF2FFFFFF, Brand.display(this));
        clockView.setLetterSpacing(0.05f);
        right.addView(clockView);
        weatherView = Brand.text(this, "", 15, Brand.DIM, Brand.body(this));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = dp(4);
        right.addView(weatherView, wl);
        head.addView(right);
        col.addView(head);

        // Tabs.
        tabs.add(new Tab(TabKind.TOP, null, "Top Stories"));
        for (Section s : Section.values()) if (Sources.covers(s)) tabs.add(new Tab(TabKind.SECTION, s, s == Section.TECH ? "Tech" : s.label));
        tabs.add(new Tab(TabKind.SAVED, null, "★ Saved"));
        tabs.add(new Tab(TabKind.PLAY, null, "▶ Play"));
        tabs.add(new Tab(TabKind.LISTEN, null, "🔊 Listen"));
        tabs.add(new Tab(TabKind.SETTINGS, null, "⚙"));
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setClipToPadding(false);
        tabScroll.setPadding(dp(34), dp(14), dp(34), dp(10));
        tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabRow);
        for (Tab t : tabs) {
            TextView v = Brand.text(this, t.label, 15, Brand.TEXT, Brand.bodyBold(this));
            v.setPadding(dp(10), dp(7), dp(10), dp(7));   // twelve tabs fit across a 1080p screen
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.setClickable(true);
            v.setDefaultFocusHighlightEnabled(false);
            v.setOnFocusChangeListener((x, has) -> {
                styleTab(x, t);
                if (has && (t.kind == TabKind.TOP || t.kind == TabKind.SECTION || t.kind == TabKind.SAVED)) {
                    main.removeCallbacks(tabSwitch);
                    pendingTab = t;
                    main.postDelayed(tabSwitch, 260);
                }
            });
            v.setOnClickListener(x -> tabClicked(t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            tabRow.addView(v, lp);
            tabViews.add(v);
        }
        col.addView(tabScroll);

        // Stories.
        contentScroll = new FocusScroll(this);
        contentScroll.onScrolled = this::scheduleVisibleImages;
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setClipToPadding(false);
        contentScroll.setPadding(0, 0, 0, dp(30));
        contentScroll.setVerticalFadingEdgeEnabled(true);
        contentScroll.setFadingEdgeLength(dp(22));
        contentScroll.setSmoothScrollingEnabled(!Motion.reduced(this));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(40), dp(14), dp(40), 0);   // margins inside the scroll, so the focus lift and ring are never clipped
        contentScroll.addView(content);
        col.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // Ticker.
        LinearLayout ticker = new LinearLayout(this);
        ticker.setGravity(Gravity.CENTER_VERTICAL);
        ticker.setBackgroundColor(0xE6060913);
        ticker.setPadding(dp(40), dp(8), dp(40), dp(10));
        View edge = new View(this);
        edge.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Brand.CYAN, Brand.VIOLET, Brand.MAGENTA}));
        tickerLabel = Brand.text(this, "LATEST", 11, 0xFFFFFFFF, Brand.display(this));
        tickerLabel.setLetterSpacing(0.16f);
        tickerLabel.setPadding(dp(10), dp(5), dp(10), dp(5));
        tickerLabel.setBackground(Brand.pill(this, 0x3300D4FF, Brand.CYAN));
        ticker.addView(tickerLabel);
        tickerText = Brand.text(this, "", 16, 0xE6FFFFFF, Brand.body(this));
        tickerText.setSingleLine(true);
        tickerText.setHorizontallyScrolling(true);
        tickerText.setPadding(dp(16), 0, 0, 0);
        ticker.addView(tickerText, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout tickerBox = new LinearLayout(this);
        tickerBox.setOrientation(LinearLayout.VERTICAL);
        tickerBox.addView(edge, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        tickerBox.addView(ticker, new LinearLayout.LayoutParams(-1, -2));
        col.addView(tickerBox);

        noticeView = Brand.text(this, "", 17, 0xFFFFFFFF, Brand.body(this));
        noticeView.setPadding(dp(22), dp(12), dp(22), dp(12));
        noticeView.setBackground(Brand.pill(this, 0xEB0A0E1A, 0x8000D4FF));
        noticeView.setVisibility(View.GONE);
        noticeView.setElevation(dp(20));
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        nl.topMargin = dp(18);
        root.addView(noticeView, nl);

        setContentView(root);
        tab = tabs.get(0);
        for (int i = 0; i < tabs.size(); i++) styleTab(tabViews.get(i), tabs.get(i));
    }

    private Tab pendingTab;
    private final Runnable tabSwitch = () -> { if (pendingTab != null && pendingTab != tab) selectTab(pendingTab, true); };

    private Tab tabFor(Section s) {
        for (Tab t : tabs) if (t.section == s && t.kind == TabKind.SECTION) return t;
        return tabs.get(0);
    }

    private void styleTab(View v, Tab t) {
        TextView tv = (TextView) v;
        boolean sel = t == tab, foc = v.hasFocus();
        int accent = t.section != null ? t.section.accent : Brand.CYAN;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(999));
        if (foc) { g.setColor(0x2900D4FF); g.setStroke(dp(2), Brand.CYAN); }
        else if (sel) { g.setColor((0x33 << 24) | (accent & 0xFFFFFF)); g.setStroke(Math.max(1, dp(1)), (0x99 << 24) | (accent & 0xFFFFFF)); }
        else g.setColor(0);
        tv.setBackground(g);
        boolean muted = t.section != null && store != null && store.muted(t.section);
        tv.setTextColor(foc || sel ? 0xFFFFFFFF : muted ? Brand.FAINT : 0xB3FFFFFF);
        tv.setText(muted ? t.label + " ·muted" : t.label);
    }

    private void tabClicked(Tab t) {
        switch (t.kind) {
            case PLAY: startAutoplay(AutoplayFlow.Mode.PLAY); break;
            case LISTEN: startAutoplay(AutoplayFlow.Mode.LISTEN); break;
            case SETTINGS: openSettings(); break;
            default:
                main.removeCallbacks(tabSwitch);
                if (t != tab) selectTab(t, true);
                if (!cards.isEmpty()) cards.get(0).requestFocus();
        }
    }

    private void selectTab(Tab t, boolean draw) {
        tab = t;
        for (int i = 0; i < tabs.size(); i++) styleTab(tabViews.get(i), tabs.get(i));
        if (draw && loaded) { render(); contentScroll.scrollTo(0, 0); }
    }

    private Desk.Pick currentPick() {
        Set<Section> muted = store.mutedSet();
        if (tab.kind == TabKind.SAVED) {
            List<Clusterer.Cluster> out = new ArrayList<>();
            for (Story s : store.saved()) out.add(clusterOf(s));
            return new Desk.Pick(out, 0);
        }
        if (tab.kind == TabKind.SECTION) return Desk.section(clusters, tab.section, kids);
        return Desk.top(clusters, muted, kids);
    }

    /** The cluster a story belongs to now, or a cluster of its own (saved stories outlive their clusters). */
    private Clusterer.Cluster clusterOf(Story s) {
        for (Clusterer.Cluster c : clusters) for (Story x : c.stories) if (x.id.equals(s.id)) return c;
        Clusterer.Cluster one = new Clusterer.Cluster();
        one.stories.add(s);
        one.lead = s;
        return one;
    }

    /** Card views kept between renders (a tab switch or a fresh edition reuses them instead of building ~50 new ones). */
    private final java.util.EnumMap<StoryCard.Size, java.util.ArrayDeque<StoryCard>> pool = new java.util.EnumMap<>(StoryCard.Size.class);

    private void render() {
        for (StoryCard c : cards) {
            if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
            pool.computeIfAbsent(c.size, k -> new java.util.ArrayDeque<>()).push(c);
        }
        content.removeAllViews();
        cards.clear();
        long now = System.currentTimeMillis();
        Desk.Pick pick = currentPick();
        List<Clusterer.Cluster> list = pick.clusters;
        Section sec = tab.kind == TabKind.SECTION ? tab.section : null;

        if (list.isEmpty()) {
            String msg = tab.kind == TabKind.SAVED ? "Nothing saved yet.\nPress MENU on a story, or Save in the reader, to keep it here."
                    : !loaded ? "Gathering today's news…" : "Nothing new in " + (sec == null ? "the news" : sec.label) + " right now.";
            TextView t = Brand.text(this, msg, 22, Brand.DIM, Brand.body(this));
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.4f);
            t.setPadding(0, dp(90), 0, 0);
            content.addView(t, new LinearLayout.LayoutParams(-1, -2));
            updateTicker(now);
            return;
        }

        // Hero row: the lead story, and the next two beside it.
        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setClipChildren(false);
        StoryCard hero = card(list.get(0), sec, StoryCard.Size.HERO, now);
        heroRow.addView(hero, new LinearLayout.LayoutParams(0, dp(258), 1.7f));
        if (list.size() > 1) {
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setClipChildren(false);
            for (int i = 1; i <= 2 && i < list.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
                if (i == 2) lp.topMargin = dp(14);
                side.addView(card(list.get(i), sec, StoryCard.Size.SIDE, now), lp);
            }
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(258), 1f);
            sp.leftMargin = dp(16);
            heroRow.addView(side, sp);
        }
        content.addView(heroRow);

        int end = Math.min(list.size(), 3 + MAX_GRID);
        if (end > 3) content.addView(sectionLabel(tab.kind == TabKind.SAVED ? "SAVED STORIES" : "MORE STORIES"));
        for (int i = 3; i < end; i += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            for (int j = 0; j < 3; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(226), 1);
                if (j > 0) lp.leftMargin = dp(16);
                if (i + j < end) row.addView(card(list.get(i + j), sec, StoryCard.Size.TILE, now), lp);
                else row.addView(new View(this), lp);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(16);
            content.addView(row, rl);
        }

        StringBuilder foot = new StringBuilder();
        foot.append(list.size()).append(list.size() == 1 ? " story" : " stories");
        if (tab.kind != TabKind.SAVED) {
            Set<String> outlets = new java.util.TreeSet<>();
            for (Clusterer.Cluster c : list) for (Story s : c.stories) outlets.add(s.source);
            foot.append(" from ").append(outlets.size()).append(" outlets");
            if (list.size() > end) foot.append(", the newest ").append(end).append(" shown");
        }
        if (pick.hidden > 0) foot.append("   ·   ").append(pick.hidden).append(pick.hidden == 1 ? " story" : " stories").append(" hidden while kids mode is on");
        TextView f = Brand.text(this, foot.toString(), 14, Brand.FAINT, Brand.body(this));
        f.setPadding(dp(4), dp(10), 0, 0);
        content.addView(f);

        // Pictures load only for the cards on (or about to come on) screen; see loadVisibleImages.
        content.post(this::loadVisibleImages);
        updateTicker(now);
    }

    private final Runnable visibleImages = this::loadVisibleImages;
    private void scheduleVisibleImages() { main.removeCallbacks(visibleImages); main.postDelayed(visibleImages, 120); }

    /**
     * Fetches the pictures of the cards within one screen of the viewport, at the card's own
     * size, and looks up a page picture (og:image) for those whose feed had none. Cards further
     * down wait until scrolling brings them near.
     */
    private void loadVisibleImages() {
        int top = contentScroll.getScrollY(), h = contentScroll.getHeight();
        if (h == 0) return;
        android.graphics.Rect r = new android.graphics.Rect();
        for (StoryCard c : cards) {
            if (c.getParent() == null || c.story == null) continue;
            c.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(c, r);
            if (!FocusScroll.near(r.top, r.bottom, top, h)) continue;
            c.loadImage(images);
            if (c.story.image == null) { StoryCard card = c; repo.findImage(c.story, () -> card.refreshImage(images)); }
        }
    }

    private TextView sectionLabel(String s) {
        TextView t = Brand.text(this, s, 12, Brand.DIM, Brand.display(this));
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(24), 0, dp(12));
        return t;
    }

    private StoryCard card(Clusterer.Cluster c, Section sec, StoryCard.Size size, long now) {
        java.util.ArrayDeque<StoryCard> free = pool.get(size);
        StoryCard v = free == null || free.isEmpty() ? new StoryCard(this, size) : free.pop();
        v.animate().cancel();
        v.setScaleX(1f); v.setScaleY(1f); v.setElevation(0); v.setAlpha(1f);
        Story s = c.leadFor(sec);
        v.bind(c, s, store.isRead(s.id), store.isSaved(s.id), Desk.breaking(c, now), now, images);
        v.setOnClickListener(x -> openReader(s, c));
        cards.add(v);
        return v;
    }

    private void showEmpty(boolean loading) {
        content.removeAllViews();
        TextView t = Brand.text(this, loading ? "Gathering today's news from " + Sources.ALL.size() + " outlets…"
                : "Can't reach the news right now. Friday will keep trying every few minutes.", 22, Brand.DIM, Brand.body(this));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(110), 0, 0);
        content.addView(t, new LinearLayout.LayoutParams(-1, -2));
        tabViews.get(0).requestFocus();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            SimpleDateFormat cf = new SimpleDateFormat("h:mm", Locale.US), af = new SimpleDateFormat(" a", Locale.US), df = new SimpleDateFormat("EEEE, MMMM d", Locale.US);
            cf.setTimeZone(zone()); af.setTimeZone(zone()); df.setTimeZone(zone());
            SpannableString s = new SpannableString(cf.format(d) + af.format(d));
            s.setSpan(new RelativeSizeSpan(0.5f), s.length() - 3, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(Brand.DIM), s.length() - 3, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clockView.setText(s);
            dateView.setText(df.format(d).toUpperCase(Locale.ROOT));
            updateStamp();
            main.removeCallbacks(this);
            main.postDelayed(this, 20_000);
        }
    };

    String clockText() {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(zone());
        return f.format(new Date());
    }

    private void updateStamp() {
        if (plan == null) { stampView.setText(""); return; }
        long now = System.currentTimeMillis();
        String s;
        if (repo.busy()) s = plan.lastSuccess() == 0 ? "Fetching the news…" : "Updating…  ·  " + RefreshPlan.label(plan.lastSuccess(), now, zone()).replace("Updated ", "last updated ");
        else if (offline && plan.lastSuccess() > 0) s = "Offline  ·  showing the update from " + RefreshPlan.label(plan.lastSuccess(), now, zone()).replace("Updated ", "");
        else s = RefreshPlan.label(plan.lastSuccess(), now, zone()) + (lastOk > 0 ? "  ·  " + lastOk + " outlets" : "");
        stampView.setText(s);
        stampView.setTextColor(plan.stale(now) && plan.lastSuccess() > 0 && !repo.busy() ? 0xCCFFFFFF : Brand.FAINT);
    }

    private void updateWeather() {
        if (weather == null) return;
        String where = NewsPlace.label().toUpperCase(java.util.Locale.ROOT);
        SpannableString s = new SpannableString(weather.glyph() + "  " + weather.line() + (where.isEmpty() ? "" : "   " + where));
        int a = s.length() - where.length();
        s.setSpan(new ForegroundColorSpan(Brand.FAINT), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.8f), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        weatherView.setText(s);
        weatherView.setTextColor(0xD9FFFFFF);
    }

    private final Runnable tickerRotate = new Runnable() {
        int i = 0;
        @Override public void run() {
            if (tickerItems.isEmpty()) return;
            i = (i + 1) % tickerItems.size();
            tickerText.setText(tickerItems.get(i));
            main.postDelayed(this, 8000);
        }
    };
    private final List<CharSequence> tickerItems = new ArrayList<>();

    private void updateTicker(long now) {
        List<Clusterer.Cluster> t = Desk.ticker(clusters, store.mutedSet(), kids, now, 14);
        boolean breaking = !t.isEmpty() && Desk.breaking(t.get(0), now);
        tickerLabel.setText(breaking ? "BREAKING" : "LATEST");
        tickerLabel.setBackground(Brand.pill(this, breaking ? 0xE6D4007A : 0x3300D4FF, breaking ? 0 : Brand.CYAN));
        tickerItems.clear();
        android.text.SpannableStringBuilder all = new android.text.SpannableStringBuilder();
        for (Clusterer.Cluster c : t) {
            android.text.SpannableStringBuilder one = new android.text.SpannableStringBuilder();
            int a = one.length();
            one.append(c.lead.source.toUpperCase(Locale.ROOT));
            one.setSpan(new ForegroundColorSpan(c.lead.section.accent), a, one.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            one.setSpan(new RelativeSizeSpan(0.8f), a, one.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            one.append("   ").append(c.lead.title);
            tickerItems.add(one);
            if (all.length() > 0) {
                int s = all.length();
                all.append("        ◆        ");
                all.setSpan(new ForegroundColorSpan(0x667B61FF), s, all.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            all.append(one);
        }
        main.removeCallbacks(tickerRotate);
        if (Motion.reduced(this)) {
            tickerText.setEllipsize(TextUtils.TruncateAt.END);
            tickerText.setText(tickerItems.isEmpty() ? "" : tickerItems.get(0));
            main.postDelayed(tickerRotate, 8000);
        } else {
            tickerText.setText(all);
            tickerText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            tickerText.setMarqueeRepeatLimit(-1);
            tickerText.setSelected(true);
        }
    }

    private final Runnable hideNotice = () -> noticeView.setVisibility(View.GONE);
    void showNotice(String s) {
        noticeView.setText(s);
        noticeView.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 3500);
    }

    // ------------------------------------------------------------- autoplay -----

    /** PLAY: silent slideshow. LISTEN: Friday reads each story (silent if this TV has no voice). */
    private void startAutoplay(AutoplayFlow.Mode mode) {
        if (clusters.isEmpty()) { showNotice("No stories yet. One moment…"); return; }
        List<Clusterer.Cluster> q = new ArrayList<>(currentPick().clusters);
        if (q.isEmpty() || tab.kind == TabKind.SAVED && q.size() < 2) q = Desk.top(clusters, store.mutedSet(), kids).clusters;
        if (q.size() > 30) q = new ArrayList<>(q.subList(0, 30));
        if (mode == AutoplayFlow.Mode.LISTEN && narrator() == null) showNotice("Voice is off in News's settings, so this plays silently.");
        push(new AutoplayLayer(this, this, q, mode, store.playSeconds(), tab.kind == TabKind.SECTION ? tab.section : null,
                null, null, null, null, this::clockText, this));
    }

    private boolean briefingWaited = false;

    private static String inPlace() { String p = NewsPlace.label(); return p.isEmpty() ? "" : " in " + p; }

    private void startBriefing() {
        List<Clusterer.Cluster> q = Desk.briefing(clusters, store.mutedSet(), kids, 5);
        if (q.isEmpty()) return;
        // Give the local weather a moment to arrive, so Friday can say it.
        if (weather == null && NewsPlace.isSet() && !briefingWaited) { briefingWaited = true; main.postDelayed(this::startBriefing, 2500); return; }
        briefingWaited = false;
        java.util.Calendar cal = java.util.Calendar.getInstance(zone());
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        String hello = h < 12 ? "Good morning." : h < 17 ? "Good afternoon." : "Good evening.";
        String count = q.size() == 5 ? "five" : String.valueOf(q.size());
        String shown = hello + " Here are today's top " + count + ".";
        String spoken = hello + " Here are the top " + count + " stories" + (weather != null ? ", and it's " + weather.temp + " degrees" + inPlace() + "." : ".");
        String note = weather == null ? null : weather.glyph() + "  " + weather.line() + inPlace();
        push(new AutoplayLayer(this, this, q, AutoplayFlow.Mode.LISTEN, store.playSeconds(), null,
                spoken, shown, note, "That's your briefing. The rest of the news is here whenever you want it.", this::clockText, this));
    }

    // ------------------------------------------------------- reader actions -----

    @Override public boolean isSaved(Story s) { return store.isSaved(s.id); }
    @Override public boolean toggleSaved(Story s) {
        boolean on = store.toggleSaved(s);
        showNotice(on ? "Saved. Find it under ★ Saved." : "Removed from Saved.");
        for (StoryCard c : cards) if (c.story.id.equals(s.id)) c.setSaved(on);
        return on;
    }
    @Override public void markRead(Story s) { if (!store.isRead(s.id)) work.execute(() -> store.markRead(s.id, System.currentTimeMillis())); }
    @Override public Images images() { return images; }
    @Override public NewsRepo repo() { return repo; }
    @Override public long now() { return System.currentTimeMillis(); }
    /** Friday's voice for News (her ElevenLabs voice when there's a key, else the TV's); null when voice is off. */
    @Override public Narrator narrator() {
        if (!prefs.voiceOn()) return null;
        if (narrator == null) {
            android.content.Context app = getApplicationContext();
            narrator = new Narrator(this, text -> FridayTv.voice(app, text), prefs.voiceSpeed(), prefs.voiceVolume());
        }
        return narrator;
    }

    @Override public void openReader(Story s, Clusterer.Cluster c) {
        push(new ReaderLayer(this, this, s, c, this));
        for (StoryCard card : cards) if (card.story.id.equals(s.id)) card.bind(card.cluster, s, true, store.isSaved(s.id), Desk.breaking(card.cluster, now()), now(), images);
    }

    @Override public void openTakes(Story current, Clusterer.Cluster c) {
        List<ListLayer.Row> rows = new ArrayList<>();
        List<Story> all = new ArrayList<>(c.stories);
        all.sort((a, b) -> Long.compare(b.published, a.published));
        Set<String> seen = new java.util.HashSet<>();
        for (Story s : all) {
            if (s.id.equals(current.id) || !seen.add(s.source + "|" + s.title)) continue;
            rows.add(new ListLayer.Row(s.source + "  —  " + Html.clip(s.title, 90), () -> RefreshPlan.age(s.published, now()) + " ago", () -> {
                Layer top = layers.get(layers.size() - 1);
                pop(top);
                openReader(s, c);
            }));
        }
        push(new ListLayer(this, this, "Other takes", rows, "The same story from " + c.sourceCount() + " outlets. Compare how each one tells it."));
    }

    /** Friday Studio, through the add-on contract: Friday's own card asks the person before anything is made or spent. */
    @Override public void makePodcast(Story s, Clusterer.Cluster c, Readability.Article a) {
        StringBuilder text = new StringBuilder();
        if (a != null) for (String p : a.paragraphs) {
            String t = p.startsWith(Readability.HEADING) ? p.substring(Readability.HEADING.length()) : p;
            text.append(t).append("\n\n");
        }
        if (text.length() == 0) text.append(s.summary);
        // The other outlets News grouped with this story: if the article is only a blurb, Studio reads these.
        org.json.JSONArray related = new org.json.JSONArray();
        if (c != null) for (Story o : c.otherTakes()) {
            if (o.link.isEmpty()) continue;
            try { related.put(new org.json.JSONObject().put("title", o.title).put("source", o.source).put("link", o.link).put("summary", o.summary)); }
            catch (org.json.JSONException ignored) { }
        }
        String body = text.toString().trim();
        work.execute(() -> {
            boolean ok = FridayTv.studio(this, s.title, s.source, s.link, body, related.toString());
            if (!ok) main.post(() -> android.widget.Toast.makeText(this, "Friday Studio needs Agent Friday on this TV.", android.widget.Toast.LENGTH_LONG).show());
        });
    }

    @Override public void askFriday(Story s, Clusterer.Cluster c) {
        StringBuilder p = new StringBuilder();
        p.append("I'm reading a news story on the TV in Friday News and want your help understanding it.\n");
        p.append("Headline: \"").append(s.title).append("\"\n");
        SimpleDateFormat f = new SimpleDateFormat("EEEE h:mm a", Locale.US);
        f.setTimeZone(zone());
        p.append("Outlet: ").append(s.source).append(", published ").append(f.format(new Date(s.published))).append(" local time.\n");
        if (!s.summary.isEmpty()) p.append("Summary: ").append(s.summary).append("\n");
        List<Story> others = c == null ? Collections.emptyList() : c.otherTakes();
        if (!others.isEmpty()) {
            p.append("Other outlets covering it:");
            for (int i = 0; i < Math.min(5, others.size()); i++) p.append(i == 0 ? " " : "; ").append(others.get(i).source).append(": \"").append(others.get(i).title).append("\"");
            p.append("\n");
        }
        p.append("In a few spoken sentences: explain what's going on and the background, point out where the outlets' accounts differ, ")
         .append("and say what is still unknown or missing. Be clear about what comes from these reports and what is your own background ")
         .append("knowledge, and don't present anything as current fact if it may have changed since your training.");
        String label = "Tell me about: " + Html.clip(s.title, 110);
        work.execute(() -> {
            boolean ok = FridayTv.ask(this, p.toString(), label);
            if (!ok) main.post(() -> android.widget.Toast.makeText(this, "Asking Friday needs Agent Friday on this TV.", android.widget.Toast.LENGTH_LONG).show());
        });
    }

    // -------------------------------------------------------------- settings -----

    private ListLayer settings;

    private void openSettings() {
        List<ListLayer.Row> rows = new ArrayList<>();
        rows.add(new ListLayer.Row("▶ Play: time per story", () -> store.playSeconds() + " s for an ordinary story (longer ones get more)", () -> {
            int[] ch = AutoplayFlow.SILENT_CHOICES_S;
            int now = store.playSeconds(), i = 0;
            while (i < ch.length && ch[i] != now) i++;
            store.setPlaySeconds(ch[(i + 1) % ch.length]);
            settings.refresh();
        }));
        rows.add(new ListLayer.Row("🔊 Listen", () -> prefs.voiceOn() ? "Friday reads each story, then the next" : "Voice is off in News's settings", null));
        rows.add(new ListLayer.Row("Refresh now", () -> plan == null ? "" : RefreshPlan.label(plan.lastSuccess(), now(), zone()), () -> {
            if (plan != null && plan.manualAllowed(now())) { refreshNow(); showNotice("Fetching the latest…"); }
            else showNotice("Just updated. Try again in a minute.");
        }));
        for (Section s : Section.values()) {
            if (!Sources.covers(s)) continue;
            rows.add(new ListLayer.Row(s.label, () -> store.muted(s) ? "Muted" : "Shown", () -> {
                store.setMuted(s, !store.muted(s));
                settings.refresh();
                for (int i = 0; i < tabs.size(); i++) styleTab(tabViews.get(i), tabs.get(i));
            }));
        }
        rows.add(new ListLayer.Row("Kids filter", () -> kids ? "On: kids mode is on" : "Off: follows kids mode", null));
        rows.add(new ListLayer.Row("Outlets", () -> Sources.ALL.size() + " public feeds, fetched by this TV", null));
        settings = new ListLayer(this, this, "Settings", rows,
                "Muted sections stay out of Top Stories, autoplay and the ticker. Saved stories, read marks and the last update are kept on this TV only. No trackers, no ads, no analytics.");
        push(settings);
    }

    // ---------------------------------------------------------------- layers -----

    /** What had focus on the browse screen when the first layer opened; it gets focus back when the last one closes. */
    private View browseFocus;

    @Override public void push(Layer l) {
        if (layers.isEmpty()) browseFocus = getCurrentFocus();
        for (Layer x : layers) x.setVisibility(View.INVISIBLE);
        layers.add(l);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        noticeView.bringToFront();
        l.focusFirst();
    }

    @Override public void pop(Layer l) {
        if (!layers.remove(l)) return;
        root.removeView(l);
        l.closed();
        if (l == settings) { settings = null; if (loaded) render(); }
        if (!layers.isEmpty()) {
            Layer t = layers.get(layers.size() - 1);
            t.setVisibility(View.VISIBLE);
            t.focusFirst();
        } else {
            // Removing the layer lets Android give focus to the first focusable view (the first tab),
            // so the next OK would land there. Put it back where it was (or on the lead story if the
            // screen was rebuilt meanwhile).
            View back = browseFocus;
            browseFocus = null;
            if (back != null && back.isAttachedToWindow() && back.isShown()) back.requestFocus();
            else if (!cards.isEmpty()) cards.get(0).requestFocus();
        }
    }

    /**
     * Every raw key goes through the app's one input layer (ui/InputLayer, as Friday does):
     * the remote's bursts become one logical press, and route() sees only logical events, so each
     * key reaches exactly one handler once. News has no WebView, so nothing else can take a key.
     */
    private final ai.futurespeak.friday.tv.ui.InputLayer input = new ai.futurespeak.friday.tv.ui.InputLayer(this::route);

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    /** A repeat of a held D-pad arrow (repeatCount > 0), which News ignores. Package-private for tests. */
    static boolean heldArrow(KeyEvent e) { return heldArrow(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN, e.getRepeatCount()); }

    static boolean heldArrow(int key, boolean down, int repeatCount) {
        return down && repeatCount > 0 && key >= 19 && key <= 22;   // KEYCODE_DPAD_UP, DOWN, LEFT, RIGHT
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) input.reset();
    }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) lastKeyAt = android.os.SystemClock.uptimeMillis();   // a fresh edition waits for the remote to rest
        // A held arrow moves one step on every News screen (browse, tabs, lists, reader, autoplay):
        // the input layer's hold repeats stop here, before any view or layer sees them.
        if (heldArrow(e)) return true;
        Layer top = layers.isEmpty() ? null : layers.get(layers.size() - 1);
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // One step back per press (held Back doesn't repeat): close the top layer, then go to Top Stories, then leave.
            if (down && e.getRepeatCount() == 0) {
                if (top != null) top.back();
                else if (tab != tabs.get(0)) { selectTab(tabs.get(0), true); tabViews.get(0).requestFocus(); }
                else finish();
            }
            return true;
        }
        if (top != null) {
            if (top.key(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        // One action per press: a held arrow repeats (the input layer allows that for scrolling), these never do.
        if (down && e.getRepeatCount() == 0 && (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_BOOKMARK)) {
            View f = getCurrentFocus();
            if (f instanceof StoryCard) { toggleSaved(((StoryCard) f).story); return true; }
        }
        if (down && (k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) { if (e.getRepeatCount() == 0) startAutoplay(AutoplayFlow.Mode.PLAY); return true; }
        return super.dispatchKeyEvent(e);
    }
}
