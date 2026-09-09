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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsing of the Yandex geocoder answer, kept free of Android types so the response format can be
 * pinned by JVM tests — the same split {@link BarrierDetector} and {@link CloseResolver} use.
 */
public final class GeocoderResponse {

    private GeocoderResponse() {}

    /** One found place: what to show in the list, and where it is. */
    public static final class Place {
        public final String address;
        public final double latitude;
        public final double longitude;

        Place(String address, double latitude, double longitude) {
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static List<Place> parse(String body) throws Exception {
        List<Place> places = new ArrayList<>();
        JSONArray members = new JSONObject(body)
                .getJSONObject("response")
                .getJSONObject("GeoObjectCollection")
                .getJSONArray("featureMember");

        for (int i = 0; i < members.length(); i++) {
            JSONObject geoObject = members.getJSONObject(i).optJSONObject("GeoObject");
            if (geoObject == null) continue;

            JSONObject point = geoObject.optJSONObject("Point");
            String pos = point == null ? null : point.optString("pos", null);
            if (pos == null) continue;
            // "pos" is "longitude latitude" — the opposite order to everything else in the app.
            String[] lonLat = pos.trim().split("\\s+");
            if (lonLat.length != 2) continue;

            try {
                places.add(new Place(
                        describe(geoObject),
                        Double.parseDouble(lonLat[1]),
                        Double.parseDouble(lonLat[0])));
            } catch (NumberFormatException e) {
                // A malformed entry is skipped rather than failing the whole search.
            }
        }
        return places;
    }

    /** Full postal-style address when available, else the short name the geocoder echoes back. */
    private static String describe(JSONObject geoObject) {
        JSONObject meta = geoObject.optJSONObject("metaDataProperty");
        if (meta != null) {
            JSONObject geocoderMeta = meta.optJSONObject("GeocoderMetaData");
            if (geocoderMeta != null) {
                String text = geocoderMeta.optString("text", "");
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        String name = geoObject.optString("name", "");
        String description = geoObject.optString("description", "");
        if (!name.isEmpty() && !description.isEmpty()) {
            return name + ", " + description;
        }
        return name.isEmpty() ? description : name;
    }
}
