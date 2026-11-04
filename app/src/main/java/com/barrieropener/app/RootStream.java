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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Runs one long-lived command as root and hands its output to the main thread line by line. On a
 * Monjaro the cluster joystick never reaches an app: the community tools that react to it
 * (MConfig+) do so as root, by following the ECarX input library's log or the vehicle network.
 *
 * <p>Root comes the way it comes to those tools: an app's own {@code exec("su")} is denied
 * (error 13), so the command goes through the unit's adb daemon on the loopback
 * ({@link LocalAdb}) as {@code su 0 sh -c '…'}, which the adb shell user is allowed to run.
 */
final class RootStream {
    private static final String TAG = "RootStream";

    interface Listener {
        /** Main thread; {@code line} is null once the command has ended. */
        void onLine(String line);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalAdb adb;
    private Thread worker;
    private String killCommand;

    RootStream(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param command      shell command run as root under {@code sh -c}; no single quotes
     * @param killCommand  run as root on {@link #stop()}, for commands that outlive their shell
     *                     (tcpdump); null if closing the stream is enough
     */
    void start(String command, String killCommand, Listener listener) {
        stop();
        this.killCommand = killCommand;
        worker = new Thread(() -> {
            StringBuilder report = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            try (LocalAdb a = LocalAdb.connect(context, report)) {
                adb = a;
                post(listener, report);
                a.shell("su 0 sh -c '" + command + "'", text -> {
                    pending.append(text);
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, nl).replace("\r", "");
                        pending.delete(0, nl + 1);
                        main.post(() -> listener.onLine(line));
                    }
                });
            } catch (Exception e) {
                post(listener, report);
                String why = WheelKeys.rootCause(e);
                main.post(() -> listener.onLine("adb failed: " + why));
                Log.d(TAG, "Root stream ended", e);
            }
            adb = null;
            if (pending.length() > 0) {
                String rest = pending.toString();
                main.post(() -> listener.onLine(rest));
            }
            main.post(() -> listener.onLine(null));
        }, "RootStream");
        worker.setDaemon(true);
        worker.start();
    }

    private void post(Listener listener, StringBuilder report) {
        for (String line : report.toString().split("\n")) {
            if (!line.isEmpty()) {
                main.post(() -> listener.onLine(line));
            }
        }
        report.setLength(0);
    }

    void stop() {
        LocalAdb a = adb;
        adb = null;
        if (a != null) {
            a.close();
        }
        if (killCommand != null) {
            String kill = killCommand;
            killCommand = null;
            Thread t = new Thread(() -> {
                try (LocalAdb k = LocalAdb.connect(context, new StringBuilder())) {
                    k.shell("su 0 sh -c '" + kill + "'", text -> { });
                } catch (Exception ignored) {
                    // Nothing more to do: the stream is gone with its connection.
                }
            }, "RootStreamKill");
            t.setDaemon(true);
            t.start();
        }
    }
}
