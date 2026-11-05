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
 * car lets Android see — and only as root: no key event, broadcast or vendor listener carries
 * them to an app, but the ECarX input library logs every press ({@code InputImpl}), and that log
 * is what the community tools (MConfig+) follow. Root comes through the unit's own adb daemon
 * ({@link LocalAdb}); on a unit without it nothing here happens and the popup is answered by touch.
 *
 * <p>While the question is up the keys must not also skip tracks, so the media buttons are muted
 * the way MConfig+ mutes them: the input service's compiled code names the MEDIA_BUTTON intent it
 * sends, a same-length rename to IEDIA_BUTTON makes it send nothing anyone listens to, and the
 * service is restarted to load it. Undone when the question goes. A flag committed before the
 * patch lets {@link #restoreIfLeftMuted} undo it after a crash, and a unit the driver has muted
 * themselves (MConfig+'s own switch) is left exactly as it is.
 */
final class MonjaroWheel {
    private static final String TAG = "MonjaroWheel";
    private static final String VDEX =
            "/data/dalvik-cache/arm64/system@app@XSFInputService@XSFInputService.apk@classes.vdex";
    private static final String MEDIA = "android.intent.action.MEDIA_BUTTON";
    private static final String IEDIA = "android.intent.action.IEDIA_BUTTON";
    private static final String RESTART = "pkill -f ecarx.xsf.inputservice";
    private static final String PREFS = "monjaro_wheel";
    private static final String KEY_MUTED_BY_US = "muted_by_us";

    /** Mute and unmute in order, never overlapping, across every popup and the restore. */
    private static final ExecutorService ROOT = Executors.newSingleThreadExecutor();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalAdb logStream;
    private volatile boolean running;

    MonjaroWheel(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Starts listening (and muting the player); harmless on units without adb or the service. */
    void start(WheelKeys.Listener listener, boolean muteMedia) {
        if (running) {
            return;
        }
        running = true;
        if (muteMedia) {
            ROOT.execute(() -> mute(context));
        }
        Thread reader = new Thread(() -> {
            StringBuilder pending = new StringBuilder();
            try (LocalAdb adb = LocalAdb.connect(context, new StringBuilder())) {
                if (!running) {
                    return;
                }
                logStream = adb;
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
        ROOT.execute(() -> unmute(context));
    }

    /** From the service's start: undo a mute a crashed popup left behind. */
    static void restoreIfLeftMuted(Context context) {
        Context app = context.getApplicationContext();
        if (prefs(app).getBoolean(KEY_MUTED_BY_US, false)) {
            ROOT.execute(() -> unmute(app));
        }
    }

    private static void mute(Context context) {
        try {
            String muted = run(context, "test -f " + VDEX + " && grep -c " + IEDIA + " " + VDEX);
            String plain = run(context, "test -f " + VDEX + " && grep -c " + MEDIA + " " + VDEX);
            // Only a unit in the stock state is touched: already muted (by the driver, through
            // MConfig+) or patched in some way we do not know — leave it alone.
            if (!"0".equals(muted.trim()) || "0".equals(plain.trim()) || plain.trim().isEmpty()) {
                Log.i(TAG, "Media keys not muted: state iedia=" + muted.trim() + " media=" + plain.trim());
                return;
            }
            prefs(context).edit().putBoolean(KEY_MUTED_BY_US, true).commit();
            run(context, "sed -i s#" + MEDIA + "#" + IEDIA + "#g " + VDEX + " && " + RESTART);
            Log.i(TAG, "Media keys muted");
        } catch (Exception e) {
            Log.w(TAG, "Mute failed", e);
        }
    }

    private static void unmute(Context context) {
        if (!prefs(context).getBoolean(KEY_MUTED_BY_US, false)) {
            return;
        }
        try {
            run(context, "sed -i s#" + IEDIA + "#" + MEDIA + "#g " + VDEX + " && " + RESTART);
            prefs(context).edit().putBoolean(KEY_MUTED_BY_US, false).commit();
            Log.i(TAG, "Media keys restored");
        } catch (Exception e) {
            // The flag stays set: the next popup's end or the next service start tries again.
            Log.w(TAG, "Unmute failed", e);
        }
    }

    /** One root command through adb; its whole output. */
    private static String run(Context context, String command) throws Exception {
        StringBuilder out = new StringBuilder();
        try (LocalAdb adb = LocalAdb.connect(context, new StringBuilder())) {
            adb.shell("su 0 sh -c '" + command + "'", out::append);
        }
        return out.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
