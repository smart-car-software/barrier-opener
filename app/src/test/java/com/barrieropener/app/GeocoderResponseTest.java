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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Pins the shape of the Yandex geocoder response the app relies on. */
public class GeocoderResponseTest {

    /** Trimmed to the fields the parser reads, in the order the service returns them. */
    private static final String RESPONSE = "{\"response\":{\"GeoObjectCollection\":{"
            + "\"featureMember\":["
            + "{\"GeoObject\":{"
            + "  \"metaDataProperty\":{\"GeocoderMetaData\":{"
            + "     \"text\":\"Россия, Москва, Красная площадь, 1\"}},"
            + "  \"name\":\"Красная площадь, 1\","
            + "  \"description\":\"Москва, Россия\","
            + "  \"Point\":{\"pos\":\"37.621202 55.753544\"}}},"
            + "{\"GeoObject\":{"
            + "  \"name\":\"Красная площадь\","
            + "  \"description\":\"Санкт-Петербург, Россия\","
            + "  \"Point\":{\"pos\":\"30.315868 59.939095\"}}}"
            + "]}}}";

    @Test
    public void parsesAddressAndCoordinates() throws Exception {
        List<GeocoderResponse.Place> places = GeocoderResponse.parse(RESPONSE);

        assertEquals(2, places.size());
        GeocoderResponse.Place first = places.get(0);
        assertEquals("Россия, Москва, Красная площадь, 1", first.address);
        // "pos" is "longitude latitude" — the swap is the easiest thing to get wrong here.
        assertEquals(55.753544, first.latitude, 1e-6);
        assertEquals(37.621202, first.longitude, 1e-6);
    }

    @Test
    public void fallsBackToNameAndDescriptionWithoutMetadata() throws Exception {
        List<GeocoderResponse.Place> places = GeocoderResponse.parse(RESPONSE);
        assertEquals("Красная площадь, Санкт-Петербург, Россия", places.get(1).address);
        assertEquals(59.939095, places.get(1).latitude, 1e-6);
    }

    @Test
    public void emptyCollectionYieldsNoPlaces() throws Exception {
        String empty = "{\"response\":{\"GeoObjectCollection\":{\"featureMember\":[]}}}";
        assertTrue(GeocoderResponse.parse(empty).isEmpty());
    }

    @Test
    public void skipsEntriesWithoutUsableCoordinates() throws Exception {
        String broken = "{\"response\":{\"GeoObjectCollection\":{\"featureMember\":["
                + "{\"GeoObject\":{\"name\":\"no point\"}},"
                + "{\"GeoObject\":{\"name\":\"bad pos\",\"Point\":{\"pos\":\"37.6\"}}}"
                + "]}}}";
        assertTrue(GeocoderResponse.parse(broken).isEmpty());
    }
}
