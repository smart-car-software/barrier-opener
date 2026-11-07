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
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's own journal of what it decided and why, kept on the unit so "Send logs" can tell what
 * happened at the gate an hour ago. Logcat alone cannot: a head unit's log buffer turns over in
 * minutes, and what the driver saw is long gone by the time they get to the button.
 *
 * <p>Each call also goes to logcat as usual. Lines are appended off the main thread to a file in
 * the app's storage; past {@link #TRIM_AT} lines the oldest are dropped down to {@link #KEEP}.
 */
final class DebugLog {
    private static final String FILE = "journal.txt";
    static final int KEEP = 1000;
    private static final int TRIM_AT = 1500;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DebugLog");
        t.setDaemon(true);
        return t;
    });
    private static volatile Context app;
    /** Lines in the file, counted on first use; -1 until then. Touched on the IO thread only. */
    private static int lines = -1;
    private static final long REPEAT_QUIET_MS = 60_000L;
    private static String lastKey;
    private static long lastAt;

    private DebugLog() {
    }

    /** From {@code BarrierOpenerApp}: where the journal lives. Calls before it go to logcat only. */
    static void init(Context context) {
        app = context.getApplicationContext();
    }

    static void i(String tag, String message) {
        Log.i(tag, message);
        add(tag, message, true);
    }

    static void w(String tag, String message, Throwable error) {
        Log.w(tag, message, error);
        add(tag, error == null ? message : message + ": " + rootCause(error), true);
    }

    /** "SocketTimeoutException: connect timed out" — the innermost cause, which is the one that says why. */
    static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static synchronized void add(String tag, String message, boolean foldRepeats) {
        Context c = app;
        if (c == null) {
            return;
        }
        // A question that cannot be put is retried on every fix or tick; the journal says so once
        // a minute, not every second, or it would be nothing but that within a quarter of an hour.
        long now = System.currentTimeMillis();
        String key = tag + "\t" + message;
        if (foldRepeats && key.equals(lastKey) && now - lastAt < REPEAT_QUIET_MS) {
            return;
        }
        lastKey = key;
        lastAt = now;
        String line = LogPayload.journalLine(now, tag, message);
        IO.execute(() -> append(c, line));
    }

    private static void append(Context c, String line) {
        File f = new File(c.getFilesDir(), FILE);
        if (lines < 0) {
            lines = read(f).size();
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(line);
            w.write('\n');
            lines++;
        } catch (IOException e) {
            Log.w("DebugLog", "Journal not written", e);
            return;
        }
        if (lines >= TRIM_AT) {
            List<String> all = read(f);
            List<String> kept = all.subList(Math.max(0, all.size() - KEEP), all.size());
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
                for (String s : kept) {
                    w.write(s);
                    w.write('\n');
                }
                lines = kept.size();
            } catch (IOException e) {
                Log.w("DebugLog", "Journal not trimmed", e);
            }
        }
    }

    /** The journal, oldest first. Blocking: call off the main thread. */
    static List<LogPayload.Entry> entries(Context context) {
        try {
            return IO.submit(() -> {
                List<LogPayload.Entry> out = new ArrayList<>();
                for (String s : read(new File(context.getApplicationContext().getFilesDir(), FILE))) {
                    LogPayload.Entry e = LogPayload.parseJournalLine(s);
                    if (e != null) {
                        out.add(e);
                    }
                }
                return out;
            }).get();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<String> read(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) {
                out.add(s);
            }
        } catch (IOException e) {
            Log.w("DebugLog", "Journal not read", e);
        }
        return out;
    }
}
