package com.Braille;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class BrailleSettingsMenu {
    private BrailleSettingsMenu() {}

    public static ScrollView wrap(Context context, LinearLayout actions) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(actions, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    public static void applySafePadding(View target, int padding, int insetTop, int insetBottom) {
        target.setPadding(padding, padding + Math.max(0, insetTop),
                padding, padding + Math.max(0, insetBottom));
    }
}
