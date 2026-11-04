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

/**
 * Picking one of the popup's answers with the steering-wheel buttons: which key moves the
 * selection, which one presses the selected answer, and where the selection lands. Android-free so
 * the mapping is under test — the codes are what a real wheel turned out to send, and a mistake
 * here means the driver opening a gate they meant to wave away.
 *
 * <p>Codes are matched whether they come as an Android {@code KeyEvent} or through the ECarX
 * interception: the vendor SDK reuses Android's numbers for the keys Android has, and adds its own
 * ({@code 300001…}) for the ones it doesn't.
 */
public final class WheelNavigator {

    public enum Action { PREVIOUS, NEXT, CONFIRM, NONE }

    // Android KeyEvent codes (and the same numbers in ECarX's KeyCode).
    static final int KEYCODE_DPAD_UP = 19;
    static final int KEYCODE_DPAD_DOWN = 20;
    static final int KEYCODE_DPAD_LEFT = 21;
    static final int KEYCODE_DPAD_RIGHT = 22;
    static final int KEYCODE_DPAD_CENTER = 23;
    static final int KEYCODE_ENTER = 66;
    static final int KEYCODE_NUMPAD_ENTER = 160;
    static final int KEYCODE_SYSTEM_NAVIGATION_UP = 280;
    static final int KEYCODE_SYSTEM_NAVIGATION_DOWN = 281;
    static final int KEYCODE_SYSTEM_NAVIGATION_LEFT = 282;
    static final int KEYCODE_SYSTEM_NAVIGATION_RIGHT = 283;
    // ECarX-only: the "DMSM" four-way pad Geely wheels carry.
    static final int ECARX_DMSM_LEFT = 300001;
    static final int ECARX_DMSM_RIGHT = 300002;
    static final int ECARX_DMSM_CENTER = 300003;

    /**
     * What the popup asks the vendor SDK to hand over while it is on screen. Deliberately not
     * media or volume: taking those away from the driver for half a minute to answer a question
     * they can also answer by touch would be a poor trade.
     */
    public static final int[] INTERCEPTED_KEYS = {
            KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT,
            KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER,
            KEYCODE_SYSTEM_NAVIGATION_UP, KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KEYCODE_SYSTEM_NAVIGATION_LEFT, KEYCODE_SYSTEM_NAVIGATION_RIGHT,
            ECARX_DMSM_LEFT, ECARX_DMSM_RIGHT, ECARX_DMSM_CENTER,
    };

    private WheelNavigator() {
    }

    public static Action actionFor(int keyCode) {
        switch (keyCode) {
            case KEYCODE_DPAD_LEFT:
            case KEYCODE_DPAD_UP:
            case KEYCODE_SYSTEM_NAVIGATION_LEFT:
            case KEYCODE_SYSTEM_NAVIGATION_UP:
            case ECARX_DMSM_LEFT:
                return Action.PREVIOUS;
            case KEYCODE_DPAD_RIGHT:
            case KEYCODE_DPAD_DOWN:
            case KEYCODE_SYSTEM_NAVIGATION_RIGHT:
            case KEYCODE_SYSTEM_NAVIGATION_DOWN:
            case ECARX_DMSM_RIGHT:
                return Action.NEXT;
            case KEYCODE_DPAD_CENTER:
            case KEYCODE_ENTER:
            case KEYCODE_NUMPAD_ENTER:
            case ECARX_DMSM_CENTER:
                return Action.CONFIRM;
            default:
                return Action.NONE;
        }
    }

    // The cluster joystick on a Monjaro, as broadcast by the ECarX input service.
    static final String BROADCAST_DIM_SCROLL_UP = "ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String BROADCAST_DIM_SCROLL_DOWN = "ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String BROADCAST_DIM_CONFIRM = "ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";

    /** The answer's three choices sit in a row, so "up" is towards Yes and "down" towards Wait. */
    public static Action actionForBroadcast(String action) {
        if (BROADCAST_DIM_SCROLL_UP.equals(action)) {
            return Action.PREVIOUS;
        }
        if (BROADCAST_DIM_SCROLL_DOWN.equals(action)) {
            return Action.NEXT;
        }
        if (BROADCAST_DIM_CONFIRM.equals(action)) {
            return Action.CONFIRM;
        }
        return Action.NONE;
    }

    /**
     * Moves the selection one step over the answers that can still be chosen. It stops at the
     * ends rather than wrapping: a driver counting presses without looking should not land back
     * on "Open" by pressing once too often.
     *
     * @param enabled which answers can be selected right now ("Wait" is spent once pressed)
     * @return the new index; {@code current} when there is nowhere to go
     */
    public static int move(int current, Action direction, boolean[] enabled) {
        int step = direction == Action.PREVIOUS ? -1 : direction == Action.NEXT ? 1 : 0;
        if (step == 0 || enabled == null) {
            return current;
        }
        for (int i = current + step; i >= 0 && i < enabled.length; i += step) {
            if (enabled[i]) {
                return i;
            }
        }
        return current;
    }

    /** The selection to fall back on when the selected answer stops being available. */
    public static int firstEnabled(boolean[] enabled) {
        if (enabled != null) {
            for (int i = 0; i < enabled.length; i++) {
                if (enabled[i]) {
                    return i;
                }
            }
        }
        return -1;
    }
}
