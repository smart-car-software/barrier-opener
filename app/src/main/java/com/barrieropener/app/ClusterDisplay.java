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
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/**
 * Windows on the driver's screen. On a Geely Monjaro the instrument cluster is an Android display
 * of its own (the community dashboards are ordinary apps started "on the driver's screen"), and an
 * overlay window is the way onto it that works: launching an activity there makes the system ask
 * "open this app on the current screen?" each time.
 */
final class ClusterDisplay {

    private ClusterDisplay() {
    }

    /** The display with that id, if it is not the head unit's own screen and is there now. */
    static Display find(Context context, int displayId) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        if (dm == null || displayId == Display.DEFAULT_DISPLAY) {
            return null;
        }
        return dm.getDisplay(displayId);
    }

    /**
     * Shows {@code view} as an overlay on {@code display}, on top of whatever runs there,
     * {@code width} px wide and {@code y} px from the {@code gravity} edge. Needs the overlay
     * permission the popup already relies on.
     *
     * @return the window manager holding the view, for {@link #removeOverlay}
     * @throws RuntimeException when the firmware refuses a window on that display
     */
    static WindowManager showOverlay(Context context, Display display, View view, int gravity,
                                     int width, int y) {
        Context displayContext = context.createDisplayContext(display);
        WindowManager wm = displayContext.getSystemService(WindowManager.class);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable: the cluster has no touch, and no key reaches a window there
                // either (tried); the wheel comes in through MonjaroWheel.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
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
}
