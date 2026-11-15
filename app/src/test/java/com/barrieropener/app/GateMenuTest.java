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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class GateMenuTest {

    @Test
    public void nearestFirst_closeOnlyForRequestGates() {
        GateMenu.Rows r = GateMenu.rows(Arrays.asList(
                new GateMenu.Entry(1, "Дача", false, 5000),
                new GateMenu.Entry(2, "Дом", true, 30),
                new GateMenu.Entry(3, "Офис", true, -1)), "Открыть: %s", "Закрыть: %s");
        assertEquals(Arrays.asList("Открыть: Дом", "Закрыть: Дом", "Открыть: Дача",
                "Открыть: Офис", "Закрыть: Офис"), r.text);
        assertEquals(Arrays.asList("open:2", "close:2", "open:1", "open:3", "close:3"), r.keys);
    }

    @Test
    public void withoutAFix_theOwnOrderStays() {
        GateMenu.Rows r = GateMenu.rows(Arrays.asList(
                new GateMenu.Entry(5, "B", false, -1),
                new GateMenu.Entry(4, "A", false, -1)), "%s", "x %s");
        assertEquals(Arrays.asList("B", "A"), r.text);
    }

    @Test
    public void parse_readsTheKey() {
        GateMenu.Command c = GateMenu.parse("close:12");
        assertEquals(12, c.id);
        assertTrue(c.close);
        assertFalse(GateMenu.parse("open:7").close);
        assertNull(GateMenu.parse("open:x"));
        assertNull(GateMenu.parse("Открыть: Дом"));
        assertNull(GateMenu.parse(null));
    }
}
