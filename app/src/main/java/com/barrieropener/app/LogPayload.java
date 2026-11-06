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

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What "Send logs" puts on the wire, kept Android-free so it is under test: the log server's
 * address as the driver typed it, the journal's line format, and the JSON lines VictoriaLogs
 * takes ({@code /insert/jsonline}: one object per line, {@code _msg} the text, {@code _time} when).
 */
final class LogPayload {
    /**
     * Where a bare host sends to. {@code app} and {@code device} are the streams; everything else
     * (version, session, source) is a plain field, so each send does not open a stream of its own.
     */
    static final String INSERT_PATH =
            "/insert/jsonline?_stream_fields=app,device&_msg_field=_msg&_time_field=_time";

    private static final Pattern LOGCAT_TIME =
            Pattern.compile("^(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d\\d\\d)");

    private LogPayload() {
    }

    /**
     * The URL to POST to, from what was typed in the settings: a bare host
     * ({@code logs.example.org}) gets https and {@link #INSERT_PATH}; an address with a path of
     * its own is taken as it is. Null when nothing usable was typed.
     */
    static String endpoint(String typed) {
        if (typed == null) {
            return null;
        }
        String url = typed.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        int hostStart = url.indexOf("://") + 3;
        while (url.length() > hostStart && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (hostStart >= url.length() || url.charAt(hostStart) == '/') {
            return null; // No host.
        }
        boolean hasPath = url.indexOf('/', hostStart) >= 0 || url.indexOf('?', hostStart) >= 0;
        return hasPath ? url : url + INSERT_PATH;
    }

    /** One journal entry as stored on the unit: time, tag, text — tab-separated, on one line. */
    static String journalLine(long timeMs, String tag, String message) {
        return timeMs + "\t" + oneLine(tag) + "\t" + oneLine(message);
    }

    static final class Entry {
        final long timeMs;
        final String tag;
        final String message;

        Entry(long timeMs, String tag, String message) {
            this.timeMs = timeMs;
            this.tag = tag;
            this.message = message;
        }
    }

    /** The reverse of {@link #journalLine}; null for a line that is not one (a torn last write). */
    static Entry parseJournalLine(String line) {
        if (line == null) {
            return null;
        }
        int a = line.indexOf('\t');
        int b = a < 0 ? -1 : line.indexOf('\t', a + 1);
        if (b < 0) {
            return null;
        }
        try {
            return new Entry(Long.parseLong(line.substring(0, a)), line.substring(a + 1, b),
                    line.substring(b + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\r", "").replace("\n", " ⏎ ").replace('\t', ' ');
    }

    /**
     * When a {@code logcat -v threadtime} line was written ("11-06 14:03:21.123  1234 …"), in
     * epoch ms, or -1 if it does not start with a time. Logcat leaves out the year: it is the
     * current one, unless that puts the line in the future — then it was written last year.
     */
    static long logcatTime(String line, long nowMs, TimeZone zone) {
        if (line == null) {
            return -1;
        }
        Matcher m = LOGCAT_TIME.matcher(line);
        if (!m.find()) {
            return -1;
        }
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(nowMs);
        int year = c.get(Calendar.YEAR);
        c.clear();
        c.set(year, Integer.parseInt(m.group(1)) - 1, Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5)));
        c.set(Calendar.MILLISECOND, Integer.parseInt(m.group(6)));
        long t = c.getTimeInMillis();
        if (t > nowMs + 24 * 3600_000L) {
            c.add(Calendar.YEAR, -1);
            t = c.getTimeInMillis();
        }
        return t;
    }

    /** RFC 3339 in UTC, millisecond precision — what {@code _time} is given as. */
    static String rfc3339(long timeMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(timeMs);
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
                c.get(Calendar.MILLISECOND));
    }

    /**
     * One JSON line: {@code _time} (left out when {@code timeMs < 0}, so the server stamps it
     * with the time it arrived), {@code _msg}, then {@code fields} in their order. Ends in "\n".
     */
    static String record(long timeMs, String message, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        if (timeMs >= 0) {
            sb.append("\"_time\":\"").append(rfc3339(timeMs)).append("\",");
        }
        sb.append("\"_msg\":");
        quote(sb, message == null || message.isEmpty() ? "-" : message);
        for (Map.Entry<String, String> f : fields.entrySet()) {
            sb.append(',');
            quote(sb, f.getKey());
            sb.append(':');
            quote(sb, f.getValue() == null ? "" : f.getValue());
        }
        return sb.append("}\n").toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
    }
}
