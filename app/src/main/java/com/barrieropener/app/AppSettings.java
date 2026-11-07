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
 * App-wide settings. The cluster and wheel switches that used to live here went with the cluster
 * popup to Car Notification Service ({@link NotifyClient}); their old keys are simply left unread.
 */
final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String KEY_LOG_URL = "log_url";
    private static final String KEY_LOG_USER = "log_user";
    private static final String KEY_LOG_PASSWORD = "log_password";

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
