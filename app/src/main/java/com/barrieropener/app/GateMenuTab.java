/*
 * Copyright © 2026 smart-car-software contributors
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * The gates on the cluster menu's phone tab: Car Notification Service (v1.3.0+) draws the list
 * and reads the left joystick, this app sends the rows ({@link GateMenu}) and does what the
 * pressed one says — through {@link BarrierAction}, as the wheel's answer to a question does.
 * The service asks for the rows whenever the tab opens ({@link Wanted}).
 */
final class GateMenuTab {
    static final String ID = "com.barrieropener.app";
    /** ECarX's phone tab of the cluster menu (the user's choice: navigation is route-suggest's). */
    static final int DIM_PHONE = 1;
    private static final String TAG = "GateMenu";

    private GateMenuTab() {
    }

    /** Builds and sends the rows. Reads the database: small, fine on a receiver's thread. */
    static void publish(Context c) {
        if (!NotifyClient.installed(c)) return;
        DatabaseHelper db = new DatabaseHelper(c);
        List<Barrier> barriers;
        try {
            barriers = db.getAllBarriers();
        } finally {
            db.close();
        }
        Location at = LocationService.lastKnown;
        List<GateMenu.Entry> entries = new ArrayList<>();
        for (Barrier b : barriers) {
            double d = -1;
            if (at != null) {
                float[] m = new float[1];
                Location.distanceBetween(at.getLatitude(), at.getLongitude(),
                        b.getLatitude(), b.getLongitude(), m);
                d = m[0];
            }
            entries.add(new GateMenu.Entry(b.getId(), b.getName(), b.isRequestBased(), d));
        }
        GateMenu.Rows r = GateMenu.rows(entries, c.getString(R.string.menu_open),
                c.getString(R.string.menu_close));
        PendingIntent pick = PendingIntent.getBroadcast(c, 41, new Intent(c, Pick.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotifyClient.menuTab(c, ID, DIM_PHONE, c.getString(R.string.menu_title),
                r.text.toArray(new String[0]), r.keys.toArray(new String[0]),
                c.getString(R.string.menu_empty), pick);
    }

    static void trial(Context c) {
        publish(c);
        NotifyClient.menuTrial(c, ID);
    }

    /** The service asks for the rows. Exported, so it only sends them: picks come to {@link Pick}. */
    public static final class Wanted extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (NotifyClient.ACTION_MENU_WANTED.equals(i.getAction())) publish(c);
        }
    }

    /** The driver pressed a row: by its PendingIntent only (not exported). */
    public static final class Pick extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            GateMenu.Command cmd = GateMenu.parse(i.getStringExtra("key"));
            if (cmd == null) {
                DebugLog.i(TAG, "pick without a key we know: " + i.getStringExtra("key"));
                return;
            }
            // Re-read: the list may have been sent before the barrier was edited.
            DatabaseHelper db = new DatabaseHelper(c);
            Barrier b = db.getBarrier(cmd.id);
            db.close();
            if (b == null) {
                DebugLog.i(TAG, "barrier " + cmd.id + " is gone");
                publish(c);
                return;
            }
            DebugLog.i(TAG, (cmd.close ? "close " : "open ") + b.getId() + " from the cluster menu");
            if (cmd.close) {
                BarrierAction.triggerClose(c, b);
                new PendingCloseStore(c).clear(b.getId());
            } else {
                BarrierAction.trigger(c, b);
                // As the wheel's «Да»: a barrier that asked for closing triggers arms them.
                if (b.isClosable()) new PendingCloseStore(c).markOpened(b.getId());
            }
        }
    }
}
