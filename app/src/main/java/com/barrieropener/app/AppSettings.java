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

/**
 * App-wide switches, all off by default so an update changes nothing until the driver opts in:
 * each of them only makes sense on a particular kind of head unit (a Monjaro's cluster, a rooted
 * unit with adb on), and the popup on the head unit keeps working without any of them.
 */
final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String KEY_CLUSTER = "cluster_popup";
    private static final String KEY_WHEEL = "wheel_keys";
    private static final String KEY_MUTE = "mute_media";
    /**
     * The Monjaro's instrument cluster: display #2, the 1920x1080 one of its three "HDMI
     * screens". Fixed rather than chosen — that is the car this feature is for.
     */
    static final int CLUSTER_DISPLAY = 2;

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Show the question on the driver's screen instead of the head unit. */
    static boolean clusterPopup(Context c) {
        return prefs(c).getBoolean(KEY_CLUSTER, false);
    }

    static void setClusterPopup(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_CLUSTER, on).apply();
    }

    /** Answer with the wheel's ⏮ ⏭ ⏯, read as root through adb. */
    static boolean wheelKeys(Context c) {
        return prefs(c).getBoolean(KEY_WHEEL, false);
    }

    static void setWheelKeys(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_WHEEL, on).apply();
    }

    /** While the question is up, keep those keys from also skipping tracks. */
    static boolean muteMedia(Context c) {
        return prefs(c).getBoolean(KEY_MUTE, true);
    }

    static void setMuteMedia(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_MUTE, on).apply();
    }
}
