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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rows of the gates' list on the cluster menu's phone tab: «Открыть» for every barrier and
 * «Закрыть» for those opened by a request (a webhook or the gate API — a call cannot close),
 * the nearest barrier first; a gate standing open has its «Закрыть» above its «Открыть». Each row carries a key ({@code open:<id>} / {@code close:<id>}) that
 * comes back with the driver's press, so a list rebuilt meanwhile cannot open the wrong gate.
 * Pure.
 */
final class GateMenu {
    static final class Entry {
        final long id;
        final String name;
        final boolean closable;
        /** Metres; negative when unknown (no fix yet). */
        final double distance;
        /** The gate is (believed to be) open: «Закрыть» goes first. See {@link #isOpen}. */
        final boolean open;

        Entry(long id, String name, boolean closable, double distance) {
            this(id, name, closable, distance, false);
        }

        Entry(long id, String name, boolean closable, double distance, boolean open) {
            this.id = id;
            this.name = name;
            this.closable = closable;
            this.distance = distance;
            this.open = open;
        }
    }

    static final class Rows {
        final List<String> text = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
    }

    /** A pressed row: which barrier, and whether to close it. */
    static final class Command {
        final long id;
        final boolean close;

        Command(long id, boolean close) {
            this.id = id;
            this.close = close;
        }
    }

    private GateMenu() {
    }

    /** Nearest first; those without a distance after, in their own order. */
    static Rows rows(List<Entry> entries, String open, String close) {
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> {
            boolean ka = a.distance >= 0, kb = b.distance >= 0;
            if (ka != kb) return ka ? -1 : 1;
            return ka ? Double.compare(a.distance, b.distance) : 0;
        });
        Rows r = new Rows();
        for (Entry e : sorted) {
            // The row that changes the gate's state comes first: an open gate is offered closing.
            boolean closeFirst = e.closable && e.open;
            if (closeFirst) addClose(r, e, close);
            r.text.add(String.format(open, e.name));
            r.keys.add("open:" + e.id);
            if (e.closable && !closeFirst) addClose(r, e, close);
        }
        return r;
    }

    private static void addClose(Rows r, Entry e, String close) {
        r.text.add(String.format(close, e.name));
        r.keys.add("close:" + e.id);
    }

    /**
     * Whether to treat the gate as standing open. The gate API's own state wins when it has one
     * (and the service could reach Home Assistant — otherwise it is a stale guess); moving towards
     * open or stopped half-way counts as open, since closing is then the useful command. Without
     * it — a webhook, no answer yet — the app's own memory: opened by the driver and not closed
     * or asked about since ({@link PendingCloseStore}). Unknown is closed: «Открыть» first, as
     * before.
     */
    static boolean isOpen(GateState fetched, boolean openedByUs) {
        if (fetched != null && fetched.haReachable && fetched.state != GateState.State.UNKNOWN) {
            switch (fetched.state) {
                case OPEN:
                case OPENING:
                case PARTIAL:
                    return true;
                default:
                    return false;
            }
        }
        return openedByUs;
    }

    /** The key of a pressed row, or null for anything else. */
    static Command parse(String key) {
        if (key == null) return null;
        boolean close = key.startsWith("close:");
        if (!close && !key.startsWith("open:")) return null;
        try {
            return new Command(Long.parseLong(key.substring(close ? 6 : 5)), close);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
