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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Car Notification Service ({@code com.smartcar.notify}): the one app in the car that shows
 * popups — on the Monjaro's cluster and the head unit, one at a time, answered with the wheel.
 * Its contract is {@code docs/API.md} in smart-car-software/car-notification-service; this is the
 * client class from there. The service has to be listed under {@code <queries>} in the manifest:
 * with targetSdk 31 it is otherwise invisible, and both the package lookup and the broadcast fail
 * silently.
 */
final class NotifyClient {
    static final String PACKAGE = "com.smartcar.notify";
    private static final String RECEIVER = "com.smartcar.notify.RequestReceiver";
    private static final String ACTION_SHOW = "com.smartcar.notify.action.SHOW";
    private static final String ACTION_MENU_TAB = "com.smartcar.notify.action.MENU_TAB";
    private static final String ACTION_MENU_TRIAL = "com.smartcar.notify.action.MENU_TRIAL";
    static final String ACTION_MENU_WANTED = "com.smartcar.notify.action.MENU_WANTED";

    private NotifyClient() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** For the journal and the logs: "installed 1.2.0" / "not installed". */
    static String describe(Context c) {
        try {
            return "installed " + c.getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    /**
     * Puts a question. Returns whether it was handed to the service; the answer — whichever it
     * is, exactly one — comes back through {@code reply}.
     *
     * @param id       re-sending the same id replaces the question where it stands, on screen or
     *                 in the queue, rather than queueing a second one
     * @param expireMs how long it may wait in the queue behind other popups before it is no longer
     *                 worth showing; 0 for no limit
     * @param reply    {@code PendingIntent.getBroadcast(…, FLAG_UPDATE_CURRENT | FLAG_MUTABLE)}:
     *                 the service writes the answer into its extras
     */
    static boolean question(Context c, String id, String title, String[] answers,
                            long expireMs, boolean sound, PendingIntent reply) {
        Intent i = base(ACTION_SHOW, id);
        i.putExtra("type", "question");
        i.putExtra("title", title);
        i.putExtra("options", answers);
        if (expireMs > 0) {
            i.putExtra("expire_ms", expireMs);
        }
        i.putExtra("sound", sound);
        i.putExtra("reply", reply);
        try {
            c.sendBroadcast(i);
            return true;
        } catch (RuntimeException e) {
            DebugLog.w("NotifyClient", "Could not send " + id, e);
            return false;
        }
    }

    /**
     * The list in the cluster's menu (the service's docs/API.md, «Список в меню приборки»).
     * {@code pick}: {@code FLAG_MUTABLE}, the service writes the pressed row's key into it.
     */
    static void menuTab(Context c, String tab, int dimTab, String title, String[] rows,
                        String[] keys, String empty, PendingIntent pick) {
        Intent i = base(ACTION_MENU_TAB, null);
        i.putExtra("tab", tab);
        i.putExtra("dim_tab", dimTab);
        i.putExtra("title", title);
        i.putExtra("options", rows);
        i.putExtra("keys", keys);
        i.putExtra("empty", empty);
        i.putExtra("pick", pick);
        send(c, i);
    }

    static void menuTrial(Context c, String tab) {
        send(c, base(ACTION_MENU_TRIAL, null).putExtra("tab", tab));
    }

    private static void send(Context c, Intent i) {
        try {
            c.sendBroadcast(i);
        } catch (RuntimeException e) {
            DebugLog.w("NotifyClient", "Could not send " + i.getAction(), e);
        }
    }

    private static Intent base(String action, String id) {
        Intent i = new Intent(action);
        i.setClassName(PACKAGE, RECEIVER);
        i.putExtra("id", id);
        return i;
    }
}
