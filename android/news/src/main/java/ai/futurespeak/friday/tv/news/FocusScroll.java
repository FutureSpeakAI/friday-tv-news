package ai.futurespeak.friday.tv.news;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

import ai.futurespeak.friday.tv.ui.Motion;

/**
 * The story grid's scroll view, for a remote: when focus moves to a card off screen, the grid
 * glides (instead of jumping) so the focused row sits a quarter of the way down, with the row
 * above it still in view. Android's ScrollView jumps there in one frame (scrollBy from
 * requestChildFocus); this intercepts that jump and runs it as a smooth scroll instead.
 */
final class FocusScroll extends ScrollView {
    private boolean holdJump = false;
    private final Rect r = new Rect();
    /** Called after each scroll step settles enough to show new rows (for lazy pictures). */
    Runnable onScrolled;

    FocusScroll(Context c) { super(c); }

    @Override public void requestChildFocus(View child, View focused) {
        holdJump = true;
        try { super.requestChildFocus(child, focused); }   // its own scrollBy is held back by scrollTo below
        finally { holdJump = false; }
        if (focused == null || getChildCount() == 0) return;
        focused.getDrawingRect(r);
        offsetDescendantRectToMyCoords(focused, r);
        int target = target(r.top, r.height(), getHeight(), getChildAt(0).getHeight() + getPaddingBottom());
        if (target == getScrollY()) return;
        if (Motion.reduced(getContext())) super.scrollTo(getScrollX(), target);
        else smoothScrollTo(getScrollX(), target);
    }

    @Override public void scrollTo(int x, int y) {
        if (holdJump) return;
        super.scrollTo(x, y);
    }

    @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (onScrolled != null) onScrolled.run();
    }

    /** Whether content between {@code top} and {@code bottom} is within half a screen above or a screen below the viewport (pictures load there). */
    static boolean near(int top, int bottom, int scrollY, int viewport) {
        return bottom >= scrollY - viewport / 2 && top <= scrollY + 2 * viewport;
    }

    /**
     * Where to scroll so the focused row ({@code top}, height {@code h}) sits with its top a
     * quarter of the way down, the row above still peeking in; clamped to the content. Moving
     * along a row keeps the same top, so it never scrolls. Package-private for tests.
     */
    static int target(int top, int h, int viewport, int content) {
        int max = Math.max(0, content - viewport);
        return Math.max(0, Math.min(max, top - viewport / 4));
    }
}
