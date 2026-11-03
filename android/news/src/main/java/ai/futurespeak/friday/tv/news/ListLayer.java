package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Layer;

/** A titled list of rows for Friday News (its settings, and other outlets' takes on a story). */
final class ListLayer extends Layer {
    static final class Row {
        final String label; final Supplier<String> value; final Runnable action;
        Row(String label, Supplier<String> value, Runnable action) { this.label = label; this.value = value; this.action = action; }
    }

    private final List<Row> rows;
    private final List<TextView> values = new ArrayList<>();
    private View first;

    ListLayer(Context c, Host host, String title, List<Row> rows, String footer) {
        super(c, host);
        this.rows = rows;
        setBackgroundColor(0xF2060913);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Brand.dp(c, 80), Brand.dp(c, 34), Brand.dp(c, 80), Brand.dp(c, 18));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(NewsActivity.logo(c, 12, 20));
        TextView t = Brand.text(c, title.toUpperCase(java.util.Locale.ROOT), 18, Brand.DIM, Brand.display(c));
        t.setLetterSpacing(0.14f);
        t.setPadding(Brand.dp(c, 28), Brand.dp(c, 14), 0, 0);
        head.addView(t);
        col.addView(head);

        ScrollView sv = new ScrollView(c);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setPadding(0, Brand.dp(c, 18), 0, Brand.dp(c, 18));
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        col.addView(Brand.text(c, footer, 14, Brand.FAINT, Brand.body(c)));
        addView(col, new LayoutParams(-1, -1));

        for (Row r : rows) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setFocusable(true);
            line.setClickable(r.action != null);
            line.setDefaultFocusHighlightEnabled(false);
            line.setBackground(Brand.focusable(c, 14, Brand.CYAN));
            line.setPadding(Brand.dp(c, 24), Brand.dp(c, 14), Brand.dp(c, 24), Brand.dp(c, 14));
            TextView l = Brand.text(c, r.label, 20, Brand.TEXT, Brand.body(c));
            l.setLineSpacing(0, 1.15f);
            line.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            TextView v = Brand.text(c, "", 18, Brand.CYAN, Brand.body(c));
            v.setPadding(Brand.dp(c, 20), 0, 0, 0);
            line.addView(v);
            values.add(v);
            if (r.action != null) line.setOnClickListener(x -> r.action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Brand.dp(c, 8);
            list.addView(line, lp);
            if (first == null) first = line;
        }
        refresh();
    }

    void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Supplier<String> s = rows.get(i).value;
            values.get(i).setText(s == null ? "" : s.get());
        }
    }

    @Override public void focusFirst() { if (first != null) first.requestFocus(); }
    @Override public boolean blocksTalk() { return false; }
}
