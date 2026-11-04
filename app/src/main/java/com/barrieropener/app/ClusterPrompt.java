/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barrieropener.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A copy of the popup's question on the driver's screen. It mirrors the popup rather than
 * replacing it: the cluster has no touch, so the head-unit popup stays where a passenger can tap
 * it, and the copy is what the driver reads and answers with the wheel.
 *
 * <p>Drawn as an overlay window on the chosen display. On a Monjaro that is what works: launching
 * an activity there instead makes the system ask "open this app on the current screen?" every
 * time. Which display is the cluster is chosen once from the diagnostics screen — every secondary
 * display on that unit calls itself "HDMI screen", so there is nothing reliable to guess from.
 */
final class ClusterPrompt {
    private static final String TAG = "ClusterPrompt";
    private static final String PREFS = "cluster_prompt";
    private static final String KEY_DISPLAY = "display_id";
    static final int NONE = -1;

    /** Yes / No / Wait colours, restored when "Wait" becomes available again. */
    private static final int[] BACKGROUNDS = {R.drawable.button_green_background,
            R.drawable.button_red_background, R.drawable.button_blue_background};

    private final Context context;
    private WindowManager wm;
    private View card;
    private View[] answers;

    ClusterPrompt(Context context) {
        this.context = context;
    }

    static int chosenDisplay(Context context) {
        return prefs(context).getInt(KEY_DISPLAY, NONE);
    }

    static void chooseDisplay(Context context, int displayId) {
        prefs(context).edit().putInt(KEY_DISPLAY, displayId).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Shows (or re-titles) the copy; quietly does nothing without a chosen, present display. */
    void show(String title) {
        if (card != null) {
            ((TextView) card.findViewById(R.id.clusterTitle)).setText(title);
            return;
        }
        int id = chosenDisplay(context);
        if (id == NONE) {
            return;
        }
        Display display = null;
        for (Display d : ClusterDisplay.secondaryDisplays(context)) {
            if (d.getDisplayId() == id) {
                display = d;
            }
        }
        if (display == null) {
            return; // The cluster is not up yet, or the choice was made on another unit.
        }
        try {
            View v = LayoutInflater.from(new ContextThemeWrapper(
                    context.createDisplayContext(display), R.style.Theme_BarrierOpener))
                    .inflate(R.layout.view_cluster_prompt, null);
            ((TextView) v.findViewById(R.id.clusterTitle)).setText(title);
            answers = new View[]{v.findViewById(R.id.clusterYes),
                    v.findViewById(R.id.clusterNo), v.findViewById(R.id.clusterWait)};
            wm = ClusterDisplay.showOverlay(context, display, v, 0);
            card = v;
        } catch (RuntimeException e) {
            // No overlay permission, or a firmware that refuses windows there: the head-unit
            // popup still asks the question.
            Log.w(TAG, "Cluster copy not shown", e);
        }
    }

    boolean isShowing() {
        return card != null;
    }

    /** Mirrors the popup's selection and which answers can still be chosen. */
    void render(int selected, boolean[] enabled) {
        if (answers == null) {
            return;
        }
        for (int i = 0; i < answers.length && i < enabled.length; i++) {
            answers[i].setEnabled(enabled[i]);
            answers[i].setBackgroundResource(enabled[i] ? BACKGROUNDS[i] : R.drawable.button_gray_background);
        }
        WheelKeys.showSelection(answers, selected);
    }

    void dismiss() {
        ClusterDisplay.removeOverlay(wm, card);
        wm = null;
        card = null;
        answers = null;
    }
}
