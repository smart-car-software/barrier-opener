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

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Runs one long-lived command as root and hands its output to the main thread line by line. On a
 * Monjaro the cluster joystick never reaches an app: the community tools that react to it (MConfig+)
 * do so as root, by following the ECarX input library's log or the vehicle network. Many owners'
 * units are rooted for those tools already; on one that is not, {@code su} is missing and this
 * reports that and does nothing.
 *
 * <p>{@code su 0 sh -c '…'} is the form those tools use, so it is the one the unit's su is known to
 * accept.
 */
final class RootStream {
    private static final String TAG = "RootStream";

    interface Listener {
        /** Main thread; {@code line} is null once the command has ended. */
        void onLine(String line);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Process process;
    private Thread reader;
    private String killCommand;

    /**
     * @param command      shell command run under {@code sh -c}; must not contain single quotes
     * @param killCommand  run as root on {@link #stop()}, for commands that outlive their shell
     *                     (tcpdump); null if destroying the process is enough
     */
    void start(String command, String killCommand, Listener listener) {
        stop();
        this.killCommand = killCommand;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "0", "sh", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            process = p;
            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        main.post(() -> listener.onLine(l));
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Stream ended", e);
                }
                main.post(() -> listener.onLine(null));
            }, "RootStream");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            main.post(() -> {
                listener.onLine("su failed: " + WheelKeys.rootCause(e));
                listener.onLine(null);
            });
        }
    }

    boolean isRunning() {
        return process != null;
    }

    void stop() {
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
        }
        if (killCommand != null) {
            String kill = killCommand;
            killCommand = null;
            new Thread(() -> {
                try {
                    new ProcessBuilder("su", "0", "sh", "-c", kill).start().waitFor();
                } catch (Exception ignored) {
                    // Nothing more to do: the process is gone or su is.
                }
            }, "RootStreamKill").start();
        }
    }
}
