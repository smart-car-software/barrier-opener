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

import android.view.KeyEvent;
import android.view.View;

/**
 * The Android-side bits shared by everything that answers the popup from the wheel: the callback
 * shape, how a selection is shown on the head unit, and how keys and errors are described in the
 * diagnostics. Which key means what lives in {@link WheelNavigator}; where the keys come from on
 * a Monjaro, in {@link MonjaroWheel}.
 */
final class WheelKeys {

    interface Listener {
        /** Called on the main thread. {@code pressed} is false for the release. */
        void onWheelKey(int keyCode, boolean pressed);
    }

    private WheelKeys() {
    }

    /**
     * Marks the answer the wheel has selected. Size and opacity rather than Android focus: the
     * popup may be on a display, or in a window, that never gets input focus, and a focus ring is
     * too thin to read at a glance from the driver's seat anyway.
     */
    static void showSelection(View[] answers, int selected) {
        for (int i = 0; i < answers.length; i++) {
            View v = answers[i];
            if (v == null) {
                continue;
            }
            boolean on = i == selected;
            v.setAlpha(on ? 1f : (v.isEnabled() ? 0.55f : 0.35f));
            v.setScaleX(on ? 1.12f : 1f);
            v.setScaleY(on ? 1.12f : 1f);
        }
    }

    /** "200087 next" / "22 DPAD_RIGHT" — the number for the report, a name when one is known. */
    static String describeKey(int code) {
        switch (code) {
            case WheelNavigator.ECARX_MEDIA_PREVIOUS: return code + " ⏮";
            case WheelNavigator.ECARX_MEDIA_NEXT: return code + " ⏭";
            case WheelNavigator.ECARX_MEDIA_PLAY_PAUSE: return code + " ⏯";
            default: break;
        }
        String name = KeyEvent.keyCodeToString(code);
        return code + " " + (name.startsWith("KEYCODE_") ? name.substring(8) : name);
    }

    static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
