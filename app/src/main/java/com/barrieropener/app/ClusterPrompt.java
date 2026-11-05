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
import android.util.Log;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The popup's question on the driver's screen. Once it is up the head-unit popup hides itself
 * (see {@code BarrierPopupActivity.hideOnHeadUnit}) and keeps only the logic: the timer, the
 * sound, the wheel keys. Yes / No only, and no timer — the driver answers with the wheel.
 *
 * <p>Drawn as an overlay window on the cluster display. On a Monjaro that is what works:
 * launching an activity there instead makes the system ask "open this app on the current
 * screen?" every time, and the cluster has no touch to answer it with.
 */
final class ClusterPrompt {
    private static final String TAG = "ClusterPrompt";
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

    ClusterPrompt(Context context) {
        this.context = context;
    }

    /** The display to use, or {@link #NONE} while the settings switch is off. */
    static int chosenDisplay(Context context) {
        return AppSettings.clusterPopup(context) ? AppSettings.CLUSTER_DISPLAY : NONE;
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
        Display display = ClusterDisplay.find(context, id);
        if (display == null) {
            return; // The cluster is not up yet, or this is not a Monjaro.
        }
        try {
            View v = LayoutInflater.from(new ContextThemeWrapper(
                    context.createDisplayContext(display), R.style.Theme_BarrierOpener))
                    .inflate(R.layout.view_cluster_prompt, null);
            ((TextView) v.findViewById(R.id.clusterTitle)).setText(title);
            // Yes / No only: on the driver's screen the question has no timer to pause, so
            // "Wait" would mean nothing — it stays until answered.
            v.findViewById(R.id.clusterWait).setVisibility(View.GONE);
            answers = new View[]{v.findViewById(R.id.clusterYes), v.findViewById(R.id.clusterNo)};
            wm = ClusterDisplay.showOverlay(context, display, v,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, CARD_WIDTH_PX, CARD_TOP_PX);
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
