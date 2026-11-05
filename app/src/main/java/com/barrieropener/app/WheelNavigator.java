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
 * <p>Codes are matched whether they come as an Android {@code KeyEvent} or out of the ECarX input
 * library's log: the vendor reuses Android's numbers for the keys Android has, and adds its own
 * ({@code 200085…}, {@code 300001…}) for the ones it doesn't.
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
    // The Monjaro's right-hand media keys, as the ECarX input library logs them (InputImpl). On
    // that car these are the keys the driver can answer with: the left joystick belongs to the
    // cluster and reaches Android by no route at all.
    static final int ECARX_MEDIA_PREVIOUS = 200088;
    static final int ECARX_MEDIA_NEXT = 200087;
    static final int ECARX_MEDIA_PLAY_PAUSE = 200085;

    private WheelNavigator() {
    }

    public static Action actionFor(int keyCode) {
        switch (keyCode) {
            case KEYCODE_DPAD_LEFT:
            case KEYCODE_DPAD_UP:
            case KEYCODE_SYSTEM_NAVIGATION_LEFT:
            case KEYCODE_SYSTEM_NAVIGATION_UP:
            case ECARX_DMSM_LEFT:
            case ECARX_MEDIA_PREVIOUS:
                return Action.PREVIOUS;
            case KEYCODE_DPAD_RIGHT:
            case KEYCODE_DPAD_DOWN:
            case KEYCODE_SYSTEM_NAVIGATION_RIGHT:
            case KEYCODE_SYSTEM_NAVIGATION_DOWN:
            case ECARX_DMSM_RIGHT:
            case ECARX_MEDIA_NEXT:
                return Action.NEXT;
            case KEYCODE_DPAD_CENTER:
            case KEYCODE_ENTER:
            case KEYCODE_NUMPAD_ENTER:
            case ECARX_DMSM_CENTER:
            case ECARX_MEDIA_PLAY_PAUSE:
                return Action.CONFIRM;
            default:
                return Action.NONE;
        }
    }

    // Android's media key codes, as a media session receives them.
    static final int KEYCODE_HEADSETHOOK = 79;
    static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;
    static final int KEYCODE_MEDIA_NEXT = 87;
    static final int KEYCODE_MEDIA_PREVIOUS = 88;
    static final int KEYCODE_MEDIA_PLAY = 126;
    static final int KEYCODE_MEDIA_PAUSE = 127;

    /**
     * The same three wheel keys when they come to the popup's own media session instead — which
     * is where they go, and the only place they go, while the popup holds the media keys away
     * from the player: the ECarX key log then stays silent. Kept apart from {@link #actionFor},
     * which also reads ordinary key events: a media key there must stay a media key.
     */
    public static Action actionForMediaKey(int keyCode) {
        switch (keyCode) {
            case KEYCODE_MEDIA_PREVIOUS:
                return Action.PREVIOUS;
            case KEYCODE_MEDIA_NEXT:
                return Action.NEXT;
            case KEYCODE_MEDIA_PLAY_PAUSE:
            case KEYCODE_MEDIA_PLAY:
            case KEYCODE_MEDIA_PAUSE:
            case KEYCODE_HEADSETHOOK:
                return Action.CONFIRM;
            default:
                return Action.NONE;
        }
    }

    /**
     * Whether a press is the same one already acted on, arriving by a second route (the key log
     * and the media session can both carry it). Two presses of one key closer than this are not
     * something a driver does; a double "confirm" is the impulse a sliding gate reads as "stop".
     */
    static final long SAME_PRESS_MS = 300L;

    public static boolean isEcho(Action action, long atMs, Action lastAction, long lastAtMs) {
        return action == lastAction && atMs - lastAtMs >= 0 && atMs - lastAtMs < SAME_PRESS_MS;
    }

    /**
     * A key press out of the ECarX input library's log, e.g.
     * {@code onKeyPressed cookie=0 keyCode=200087}. Releases and every other line give -1, so a
     * press is acted on once however long the key is held.
     */
    public static int pressedKeyFromInputImplLog(String line) {
        if (line == null || !line.contains("onKeyPressed")) {
            return -1;
        }
        int at = line.indexOf("keyCode=");
        if (at < 0) {
            return -1;
        }
        int start = at + "keyCode=".length();
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
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
