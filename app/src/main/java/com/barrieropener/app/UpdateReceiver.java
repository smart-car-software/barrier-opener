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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Android's installer answers here: shows its confirmation, or tells how it ended. */
public final class UpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "Updater";
    /** Which package the session installs: this app (an update) or another one. */
    static final String EXTRA_PACKAGE = "package";

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (RuntimeException e) {
                    DebugLog.w(TAG, "installer did not open", e);
                    Toast.makeText(c, c.getString(R.string.upd_error, DebugLog.rootCause(e)),
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        String pkg = i.getStringExtra(EXTRA_PACKAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (pkg != null && !pkg.equals(c.getPackageName())) {
                // Another app (Car Notification Service): we live on to say so.
                DebugLog.i(TAG, pkg + " installed");
                Toast.makeText(c, R.string.notify_installed_now, Toast.LENGTH_LONG).show();
                return;
            }
            // Rarely heard: the install ends this process. BootReceiver logs the new version.
            DebugLog.i(TAG, "update installed");
            return;
        }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = msg == null ? String.valueOf(status) : msg;
        DebugLog.i(TAG, "update not installed: " + why);
        // Aborted is the driver's own "Cancel" on the installer's dialog: nothing to tell.
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(c, c.getString(R.string.upd_error, why), Toast.LENGTH_LONG).show();
        }
    }
}
