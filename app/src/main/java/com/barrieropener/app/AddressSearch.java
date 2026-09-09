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

import android.util.Log;

import com.yandex.mapkit.GeoObject;
import com.yandex.mapkit.GeoObjectCollection;
import com.yandex.mapkit.geometry.BoundingBox;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.search.Response;
import com.yandex.mapkit.search.SearchFactory;
import com.yandex.mapkit.search.SearchManager;
import com.yandex.mapkit.search.SearchManagerType;
import com.yandex.mapkit.search.SearchOptions;
import com.yandex.mapkit.search.SearchType;
import com.yandex.mapkit.search.Session;
import com.yandex.mapkit.search.SuggestItem;
import com.yandex.mapkit.search.SuggestOptions;
import com.yandex.mapkit.search.SuggestResponse;
import com.yandex.mapkit.search.SuggestSession;
import com.yandex.mapkit.search.SuggestType;

import java.util.ArrayList;
import java.util.List;

/**
 * Address lookup for the map: suggestions while typing, and coordinates for whatever is picked.
 *
 * Both come from MapKit's own search rather than the HTTP geocoder, which is what makes
 * as-you-type suggestions possible at all — the HTTP service has no equivalent — and keeps
 * everything on the API key the map already uses.
 *
 * Callbacks arrive on the main thread: MapKit delivers its listeners there.
 */
public final class AddressSearch {
    private static final String TAG = "AddressSearch";
    private static final int MAX_RESULTS = 8;

    private final SearchManager searchManager;
    private final SuggestSession suggestSession;
    /** Held so an in-flight resolve isn't collected before it answers. */
    private Session pendingResolve;

    public interface SuggestCallback {
        void onSuggestions(List<Suggestion> suggestions);

        void onError(String message);
    }

    public interface ResolveCallback {
        void onResolved(Point point);

        void onError(String message);
    }

    /** One line of the suggestion list. {@link #center} is null for partial, word-like entries. */
    public static final class Suggestion {
        public final String title;
        public final String searchText;
        public final Point center;

        Suggestion(String title, String searchText, Point center) {
            this.title = title;
            this.searchText = searchText;
            this.center = center;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public AddressSearch() {
        searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED);
        suggestSession = searchManager.createSuggestSession();
    }

    /**
     * @param window the visible part of the map; suggestions inside it rank first, because the
     *               same street name exists in every city and the one on screen is the one meant
     */
    public void suggest(String text, BoundingBox window, SuggestCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onSuggestions(new ArrayList<>());
            return;
        }
        SuggestOptions options = new SuggestOptions()
                .setSuggestTypes(SuggestType.GEO.value | SuggestType.BIZ.value)
                .setSuggestWords(true);

        suggestSession.suggest(text.trim(), window, options, new SuggestSession.SuggestListener() {
            @Override
            public void onResponse(SuggestResponse response) {
                List<Suggestion> result = new ArrayList<>();
                for (SuggestItem item : response.getItems()) {
                    if (result.size() >= MAX_RESULTS) break;
                    String title = item.getTitle() != null ? item.getTitle().getText() : null;
                    if (title == null || title.isEmpty()) continue;
                    String subtitle = item.getSubtitle() != null ? item.getSubtitle().getText() : null;
                    String label = (subtitle == null || subtitle.isEmpty())
                            ? title : title + " — " + subtitle;
                    result.add(new Suggestion(label, item.getSearchText(), item.getCenter()));
                }
                callback.onSuggestions(result);
            }

            @Override
            public void onError(com.yandex.runtime.Error error) {
                Log.e(TAG, "Suggest failed: " + error);
                callback.onError(describe(error));
            }
        });
    }

    /**
     * Turns a picked suggestion into a point. Most already carry one; the word-like ones don't, and
     * for those the search is run for real.
     */
    public void resolve(Suggestion suggestion, BoundingBox window, ResolveCallback callback) {
        if (suggestion.center != null) {
            callback.onResolved(suggestion.center);
            return;
        }
        SearchOptions options = new SearchOptions()
                .setSearchTypes(SearchType.GEO.value | SearchType.BIZ.value)
                .setResultPageSize(1);

        pendingResolve = searchManager.submit(suggestion.searchText, Geometry.fromBoundingBox(window),
                options, new Session.SearchListener() {
                    @Override
                    public void onSearchResponse(Response response) {
                        Point point = firstPoint(response);
                        if (point == null) {
                            callback.onError(null); // Caller phrases "nothing found".
                        } else {
                            callback.onResolved(point);
                        }
                    }

                    @Override
                    public void onSearchError(com.yandex.runtime.Error error) {
                        Log.e(TAG, "Search failed: " + error);
                        callback.onError(describe(error));
                    }
                });
    }

    private static Point firstPoint(Response response) {
        for (GeoObjectCollection.Item item : response.getCollection().getChildren()) {
            GeoObject obj = item.getObj();
            if (obj == null || obj.getGeometry().isEmpty()) continue;
            Point point = obj.getGeometry().get(0).getPoint();
            if (point != null) {
                return point;
            }
        }
        return null;
    }

    /** MapKit errors carry no message of their own; the class name is what identifies them. */
    private static String describe(com.yandex.runtime.Error error) {
        return error == null ? "" : error.getClass().getSimpleName();
    }
}
