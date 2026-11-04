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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * The cluster joystick as the ECarX input service ({@code ecarx.xsf.inputservice}) announces it:
 * not as key events to any app, but as broadcasts — scroll up, scroll down, confirm — meant for the
 * cluster ("DIM") menu. Community tools for the Monjaro (MConfig+) listen to the same broadcasts;
 * receiving them needs neither root nor a vendor library. The cluster menu still gets them too.
 */
final class DimKeyBroadcasts {
    static final String ACTION_PREFIX = "ecarx.intent.action.";
    static final String EXTRA_EVENT_TYPE = "ecarx.extra.ECARX_KEY_EVENT_TYPE";
    static final String EXTRA_ACTION_TYPE = "ecarx.extra.ECARX_KEY_ACTION_TYPE";

    /** Everything the input service is known to send; the popup acts on the three DIM ones. */
    static final String[] ACTIONS = {
            WheelNavigator.BROADCAST_DIM_SCROLL_UP,
            WheelNavigator.BROADCAST_DIM_SCROLL_DOWN,
            WheelNavigator.BROADCAST_DIM_CONFIRM,
            ACTION_PREFIX + "ECARX_KEY_RSRC_EVENT",
            ACTION_PREFIX + "ECARX_KEY_RSRC_EVENT_DOWN",
            ACTION_PREFIX + "ECARX_KEY_RSRC_EVENT_UP",
            ACTION_PREFIX + "ECARX_KEY_ISRC_EVENT",
            ACTION_PREFIX + "ECARX_KEY_ISRC_EVENT_DOWN",
            ACTION_PREFIX + "ECARX_KEY_ISRC_EVENT_UP",
            ACTION_PREFIX + "ECARX_KEY_RWECHAT_EVENT",
            ACTION_PREFIX + "ECARX_KEY_RVOICEASSIST_EVENT",
            ACTION_PREFIX + "vr_pressed",
            ACTION_PREFIX + "vr_released",
    };

    /**
     * A press may come as one broadcast or as a down/up pair, and the extras that would tell them
     * apart are undocumented; acting once per this window keeps one press from moving twice — and
     * one "confirm" from becoming the double impulse a sliding gate reads as "stop".
     */
    private static final long SAME_PRESS_MS = 350L;

    interface Listener {
        /** Main thread. {@code description} is for diagnostics: action and every extra. */
        void onDimKey(WheelNavigator.Action action, String description);
    }

    private final Context context;
    private Listener listener;
    private String lastAction;
    private long lastAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            Listener l = listener;
            if (l == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            WheelNavigator.Action wheel = WheelNavigator.actionForBroadcast(action);
            KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            boolean act;
            if (key != null) {
                act = key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0;
            } else {
                long now = SystemClock.elapsedRealtime();
                act = !action.equals(lastAction) || now - lastAt > SAME_PRESS_MS;
                lastAction = action;
                lastAt = now;
            }
            l.onDimKey(act ? wheel : WheelNavigator.Action.NONE, describe(intent));
        }
    };

    DimKeyBroadcasts(Context context) {
        this.context = context;
    }

    void start(Listener listener) {
        stop();
        this.listener = listener;
        IntentFilter filter = new IntentFilter();
        for (String a : ACTIONS) {
            filter.addAction(a);
        }
        // No permission on the receiver: the input service sends without one.
        context.registerReceiver(receiver, filter);
    }

    void stop() {
        if (listener == null) {
            return;
        }
        listener = null;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Never registered.
        }
    }

    static String describe(Intent intent) {
        StringBuilder sb = new StringBuilder(intent.getAction().replace(ACTION_PREFIX, ""));
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String k : extras.keySet()) {
                sb.append(' ').append(k.replace("ecarx.extra.", "")).append('=').append(extras.get(k));
            }
        }
        return sb.toString();
    }
}
