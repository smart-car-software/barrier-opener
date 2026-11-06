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
    private static final String KEY_LOG_URL = "log_url";
    private static final String KEY_LOG_USER = "log_user";
    private static final String KEY_LOG_PASSWORD = "log_password";
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
        set(c, KEY_CLUSTER, on);
    }

    /**
     * Every change goes into the journal, with where it came from: a switch on the car once
     * turned itself off, and nothing in the code explains it, so the next time it should show.
     */
    private static void set(Context c, String key, boolean on) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder from = new StringBuilder();
        // A tap reads "… < CompoundButton.toggle < View.performClick", a restored screen
        // "… < CompoundButton.onRestoreInstanceState".
        for (int i = 2; i < Math.min(stack.length, 9); i++) {
            from.append(i > 2 ? " < " : "").append(stack[i].getClassName()
                    .replace("com.barrieropener.app.", "")).append('.').append(stack[i].getMethodName());
        }
        DebugLog.i("AppSettings", key + " = " + on + " (from " + from + ")");
        // commit, not apply: a head unit's process can be killed seconds after a tap (the engine
        // off), and a switch that silently reverts is worse than a millisecond on the main thread.
        prefs(c).edit().putBoolean(key, on).commit();
    }

    /** All the switches on one line, for the journal. */
    static String describe(Context c) {
        return "cluster " + clusterPopup(c) + ", wheel " + wheelKeys(c) + ", keep from music "
                + muteMedia(c);
    }

    /** Answer with the wheel's ⏮ ⏭ ⏯, read as root through adb. */
    static boolean wheelKeys(Context c) {
        return prefs(c).getBoolean(KEY_WHEEL, false);
    }

    static void setWheelKeys(Context c, boolean on) {
        set(c, KEY_WHEEL, on);
    }

    /** While the question is up, keep those keys from also skipping tracks. */
    static boolean muteMedia(Context c) {
        return prefs(c).getBoolean(KEY_MUTE, true);
    }

    static void setMuteMedia(Context c, boolean on) {
        set(c, KEY_MUTE, on);
    }

    /**
     * Where "Send logs" goes ({@link LogSender}): empty until typed, since the repository is public
     * and the server is the owner's own. A bare host is enough; see {@link LogPayload#endpoint}.
     */
    static String logUrl(Context c) {
        return prefs(c).getString(KEY_LOG_URL, "");
    }

    static String logUser(Context c) {
        return prefs(c).getString(KEY_LOG_USER, "");
    }

    static String logPassword(Context c) {
        return prefs(c).getString(KEY_LOG_PASSWORD, "");
    }

    static void setLogServer(Context c, String url, String user, String password) {
        prefs(c).edit()
                .putString(KEY_LOG_URL, url.trim())
                .putString(KEY_LOG_USER, user.trim())
                .putString(KEY_LOG_PASSWORD, password)
                .apply();
    }
}
