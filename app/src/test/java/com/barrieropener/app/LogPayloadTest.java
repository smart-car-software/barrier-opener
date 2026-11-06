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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

public class LogPayloadTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    public void aBareHostGetsHttpsAndTheInsertPath() {
        assertEquals("https://logs.example.org" + LogPayload.INSERT_PATH,
                LogPayload.endpoint(" logs.example.org/ "));
        assertEquals("http://10.0.0.5:9428" + LogPayload.INSERT_PATH,
                LogPayload.endpoint("http://10.0.0.5:9428"));
    }

    @Test
    public void anAddressWithAPathIsTakenAsTyped() {
        assertEquals("https://h/insert/jsonline?_stream_fields=app",
                LogPayload.endpoint("https://h/insert/jsonline?_stream_fields=app"));
    }

    @Test
    public void nothingTypedIsNoAddress() {
        assertNull(LogPayload.endpoint(null));
        assertNull(LogPayload.endpoint("  "));
        assertNull(LogPayload.endpoint("https://"));
    }

    @Test
    public void journalLinesRoundTripOnOneLine() {
        String line = LogPayload.journalLine(1234L, "Popup", "two\nlines\tand a tab");
        assertEquals(-1, line.indexOf('\n'));
        LogPayload.Entry e = LogPayload.parseJournalLine(line);
        assertEquals(1234L, e.timeMs);
        assertEquals("Popup", e.tag);
        assertEquals("two ⏎ lines and a tab", e.message);
    }

    @Test
    public void aTornJournalLineIsSkipped() {
        assertNull(LogPayload.parseJournalLine("12"));
        assertNull(LogPayload.parseJournalLine("x\ty\tz"));
        assertNull(LogPayload.parseJournalLine(null));
    }

    @Test
    public void logcatTimeTakesTheCurrentYear() {
        long now = 1_793_000_000_000L; // 2026-10-26T07:33:20Z
        long t = LogPayload.logcatTime("10-26 07:15:03.250  123  456 D Tag: hi", now, UTC);
        assertEquals("2026-10-26T07:15:03.250Z", LogPayload.rfc3339(t));
    }

    @Test
    public void aLogcatTimeInTheFutureIsLastYears() {
        long jan = 1_767_312_000_000L; // 2026-01-02T00:00:00Z
        long t = LogPayload.logcatTime("12-31 23:00:00.000  1  1 I T: x", jan, UTC);
        assertEquals("2025-12-31T23:00:00.000Z", LogPayload.rfc3339(t));
        assertEquals(-1, LogPayload.logcatTime("--------- beginning of main", jan, UTC));
    }

    @Test
    public void recordsAreOneEscapedJsonLine() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("app", "barrier-opener");
        f.put("device", "Geely \"KX11\"");
        assertEquals("{\"_time\":\"1970-01-01T00:00:01.000Z\",\"_msg\":\"a\\\\b\\nc\\u0001\","
                        + "\"app\":\"barrier-opener\",\"device\":\"Geely \\\"KX11\\\"\"}\n",
                LogPayload.record(1000L, "a\\b\nc\u0001", f));
        assertEquals("{\"_msg\":\"-\"}\n", LogPayload.record(-1, "", new LinkedHashMap<>()));
    }
}
