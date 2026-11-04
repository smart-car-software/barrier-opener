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
import android.view.Gravity;
import android.view.KeyEvent;
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

    /**
     * Where the card goes on a Monjaro's cluster (display #2, 1920x1080, of which the top 720 rows
     * are the visible panel): centred, as wide as the navigation hint and just below it, so it
     * reads as one more cluster panel rather than something laid over the dials. Measured off
     * photos of the real cluster, hence plain pixels.
     */
    static final int CARD_WIDTH_PX = 460;
    static final int CARD_TOP_PX = 288;

    private final Context context;
    private WindowManager wm;
    private View card;
    private View[] answers;
    private WheelKeys.Listener keyListener;

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

    /**
     * Wheel keys that reach the cluster display. Its joystick drives the cluster's own menu — an
     * app on that display — so the keys go to whichever window there has focus; the card takes
     * focus while it is up and hands them over here.
     */
    void setKeyListener(WheelKeys.Listener listener) {
        this.keyListener = listener;
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
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.setOnKeyListener((view, keyCode, event) -> {
                if (WheelNavigator.actionFor(keyCode) == WheelNavigator.Action.NONE) {
                    return false; // Not ours: let the cluster have it.
                }
                WheelKeys.Listener l = keyListener;
                if (l != null && event.getRepeatCount() == 0) {
                    l.onWheelKey(keyCode, event.getAction() == KeyEvent.ACTION_DOWN);
                }
                return true;
            });
            wm = ClusterDisplay.showOverlay(context, display, v,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, CARD_WIDTH_PX, CARD_TOP_PX, true);
            v.requestFocus();
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
        paint(answers, selected, enabled);
    }

    /**
     * The cluster's way of showing a choice: the selected answer in the accent frame, the rest as
     * plain text, a spent one ("Wait" once pressed) dimmed. No scaling as on the head unit — the
     * panel is narrow and the neighbouring cluster widgets must not be crowded.
     */
    static void paint(View[] answers, int selected, boolean[] enabled) {
        for (int i = 0; i < answers.length; i++) {
            TextView v = (TextView) answers[i];
            boolean on = enabled == null || (i < enabled.length && enabled[i]);
            v.setEnabled(on);
            if (i == selected && on) {
                v.setBackgroundResource(R.drawable.cluster_answer_selected);
            } else {
                v.setBackground(null);
            }
            v.setTextColor(v.getContext().getColor(on ? R.color.cluster_text : R.color.cluster_text_dim));
        }
    }

    void dismiss() {
        ClusterDisplay.removeOverlay(wm, card);
        wm = null;
        card = null;
        answers = null;
    }
}
