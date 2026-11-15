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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Car Notification Service offered for install when it is missing: its latest GitHub Release
 * downloaded and handed to Android's installer, the same way as our own update. Asked once
 * when the app opens (after the permissions), and always available from the settings.
 */
final class NotifyInstall {
    private static final String TAG = "NotifyInstall";
    private static final String PREFS = "updater";
    private static final String KEY_ASKED = "notify_install_asked";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NotifyInstall");
        t.setDaemon(true);
        return t;
    });

    private NotifyInstall() {
    }

    /** Whether the one question at opening is still due. */
    static boolean shouldAsk(Context c) {
        return !NotifyClient.installed(c) && !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ASKED, false);
    }

    /** The question at opening; {@code then} runs when it is answered either way. */
    static void ask(Activity a, Runnable then) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply();
        new AlertDialog.Builder(a)
                .setTitle(R.string.notify_install_title)
                .setMessage(R.string.notify_install_message)
                .setPositiveButton(R.string.notify_install, (d, w) -> {
                    install(a);
                    then.run();
                })
                .setNegativeButton(R.string.action_later, (d, w) -> then.run())
                .setCancelable(false)
                .show();
    }

    /** Downloads and installs; the installer asks the driver, failures come as toasts. */
    static void install(Activity a) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.notify_allow_unknown, Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) {
                // A firmware without that screen: the installer will ask itself, or say no.
            }
            return;
        }
        Context app = a.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(a, R.string.notify_downloading, Toast.LENGTH_SHORT).show();
        WORKER.execute(() -> {
            try {
                Updater.Release r = Updater.latest(Updater.NOTIFY);
                if (r == null) throw new IOException("no APK in the release");
                File apk = Updater.download(app, r, "car-notification-service.apk");
                DebugLog.i(TAG, "downloaded " + r.tag);
                Updater.installWithPrompt(app, apk, NotifyClient.PACKAGE);
            } catch (IOException | RuntimeException e) {
                DebugLog.w(TAG, "install failed", e);
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> Toast.makeText(app, app.getString(R.string.upd_error, m),
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}
