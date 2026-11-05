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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answering the popup with the Monjaro's right-hand media keys (⏮ ⏭ ⏯), the only wheel keys that
 * car lets Android see — and only as root. Every unprivileged route was tried on the car and none
 * carries them to an app: no key event, no input-service broadcast, neither the ECarX AdaptAPI
 * interception (accepted, never calls back) nor Geely OneOS (absent). But the ECarX input library
 * logs each press ({@code InputImpl}: "onKeyPressed cookie=0 keyCode=200087"), and following that
 * log is what the community tools (MConfig+) do. Root comes through the unit's own adb daemon
 * ({@link LocalAdb}); on a unit without it nothing happens and the popup is answered by touch.
 * The left joystick is not in that log at all — it belongs to the cluster.
 *
 * <p>Keeping those keys from also skipping the track is {@link MediaKeyCatcher}'s job, not this
 * class's.
 */
final class MonjaroWheel {
    private static final String TAG = "MonjaroWheel";

    /**
     * Test builds 0.9.7–0.9.9 muted the media keys by patching the input service's compiled code
     * (as MConfig+ does) and restoring it afterwards. That did not even work — the player is
     * reached through the media-session dispatch, not that broadcast — and is gone; but a unit
     * one of those builds left patched (killed mid-question) is still put right on start-up.
     */
    private static final String VDEX =
            "/data/dalvik-cache/arm64/system@app@XSFInputService@XSFInputService.apk@classes.vdex";
    private static final String MEDIA = "android.intent.action.MEDIA_BUTTON";
    private static final String IEDIA = "android.intent.action.IEDIA_BUTTON";
    private static final String RESTART = "pkill -f ecarx.xsf.inputservice";
    private static final String PREFS = "monjaro_wheel";
    private static final String KEY_MUTED_BY_US = "muted_by_us";

    private static final ExecutorService ROOT = Executors.newSingleThreadExecutor();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalAdb logStream;
    private volatile boolean running;

    MonjaroWheel(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts following the key log; harmless on units without adb or the service. */
    void start(WheelKeys.Listener listener) {
        if (running) {
            return;
        }
        running = true;
        Thread reader = new Thread(() -> {
            StringBuilder pending = new StringBuilder();
            try (LocalAdb adb = LocalAdb.connect(context, new StringBuilder())) {
                if (!running) {
                    return;
                }
                logStream = adb;
                // -T 1: from now on, not the presses already in the log buffer.
                adb.shell("su 0 sh -c 'logcat -b main -v raw -T 1 InputImpl:V *:S'", text -> {
                    pending.append(text);
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        int code = WheelNavigator.pressedKeyFromInputImplLog(pending.substring(0, nl));
                        pending.delete(0, nl + 1);
                        if (code > 0 && running) {
                            main.post(() -> listener.onWheelKey(code, true));
                        }
                    }
                });
            } catch (Exception e) {
                Log.d(TAG, "Wheel log ended", e);
            }
            logStream = null;
        }, "MonjaroWheel");
        reader.setDaemon(true);
        reader.start();
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        LocalAdb adb = logStream;
        logStream = null;
        if (adb != null) {
            adb.close();
        }
    }

    /** From the service's start: undo the input-service patch a 0.9.7–0.9.9 test build left. */
    static void restoreIfLeftMuted(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_MUTED_BY_US, false)) {
            return;
        }
        ROOT.execute(() -> {
            try (LocalAdb adb = LocalAdb.connect(app, new StringBuilder())) {
                adb.shell("su 0 sh -c 'sed -i s#" + IEDIA + "#" + MEDIA + "#g " + VDEX
                        + " && " + RESTART + "'", text -> { });
                prefs.edit().putBoolean(KEY_MUTED_BY_US, false).commit();
                Log.i(TAG, "Input service restored");
            } catch (Exception e) {
                // The flag stays set: the next start tries again.
                Log.w(TAG, "Restore failed", e);
            }
        });
    }
}
