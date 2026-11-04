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

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The driver's screen, if Android can see it. On a Geely Monjaro the instrument cluster is an
 * Android display of its own — the community dashboards (MonjaroDev's "Dashboard") are ordinary
 * apps started "on the driver's screen" — so a window can in principle be put there either as an
 * overlay above whatever runs on it or by launching an activity onto it. Which of the two a given
 * firmware allows is not knowable from here; the diagnostics screen tries both.
 */
final class ClusterDisplay {

    private ClusterDisplay() {
    }

    /** Every display but the head unit's own screen. */
    static List<Display> secondaryDisplays(Context context) {
        List<Display> out = new ArrayList<>();
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        if (dm == null) {
            return out;
        }
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                out.add(d);
            }
        }
        return out;
    }

    static String describeAll(Context context) {
        StringBuilder r = new StringBuilder();
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        if (dm == null) {
            return "DisplayManager: null\n";
        }
        for (Display d : dm.getDisplays()) {
            r.append(describe(d)).append('\n');
        }
        Display[] presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        r.append("presentation category: ").append(presentation.length).append('\n');
        return r.toString();
    }

    static String describe(Display d) {
        Point size = new Point();
        d.getRealSize(size);
        int flags = d.getFlags();
        StringBuilder f = new StringBuilder();
        if ((flags & Display.FLAG_PRIVATE) != 0) f.append(" PRIVATE");
        if ((flags & Display.FLAG_PRESENTATION) != 0) f.append(" PRESENTATION");
        if ((flags & Display.FLAG_SECURE) != 0) f.append(" SECURE");
        if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) f.append(" PROTECTED");
        if ((flags & Display.FLAG_ROUND) != 0) f.append(" ROUND");
        return "#" + d.getDisplayId() + " \"" + d.getName() + "\" " + size.x + "x" + size.y
                + " state=" + d.getState() + " flags=0x" + Integer.toHexString(flags) + f;
    }

    /**
     * Shows {@code view} as an overlay on {@code display}, on top of whatever app owns it.
     * Needs the overlay permission the popup already relies on.
     *
     * @return the window manager holding the view, for {@link #removeOverlay}
     * @throws RuntimeException when the firmware refuses a window on that display
     */
    static WindowManager showOverlay(Context context, Display display, View view, int gravity) {
        return showOverlay(context, display, view, gravity,
                WindowManager.LayoutParams.WRAP_CONTENT, 0, false);
    }

    /** As above, {@code width} px wide (or a LayoutParams constant) and {@code y} px from the edge. */
    static WindowManager showOverlay(Context context, Display display, View view, int gravity,
                                     int width, int y) {
        return showOverlay(context, display, view, gravity, width, y, false);
    }

    /**
     * @param focusable take input focus on that display. A cluster's wheel joystick drives the
     *        cluster's own menu, which on a Monjaro is an app on that very display — so its keys
     *        go to whichever window there has focus, and a focusable overlay receives them (and
     *        takes them away from the cluster menu while it is up).
     */
    static WindowManager showOverlay(Context context, Display display, View view, int gravity,
                                     int width, int y, boolean focusable) {
        Context displayContext = context.createDisplayContext(display);
        WindowManager wm = displayContext.getSystemService(WindowManager.class);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable: the cluster has no touch and no focus to give; the wheel reaches
                // us through the vendor interception, not through this window.
                (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity == 0 ? Gravity.CENTER : gravity;
        lp.y = y;
        wm.addView(view, lp);
        return wm;
    }

    static void removeOverlay(WindowManager wm, View view) {
        if (wm == null || view == null) {
            return;
        }
        try {
            wm.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
            // Already gone with its display.
        }
    }

    /** Starts {@code intent} on {@code displayId}; throws if the system will not allow it. */
    static void launchOn(Context context, Intent intent, int displayId) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        context.startActivity(intent, options.toBundle());
    }
}
