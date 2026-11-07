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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Loads the GitHub Pages "about" page in a WebView. Falls back to a static text from
 * {@code R.string.about_fallback} when offline or the page cannot be loaded.
 */
public class AboutActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progress;
    private ScrollView fallbackContainer;
    private TextView fallbackText;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.aboutToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.aboutWebView);
        progress = findViewById(R.id.aboutProgress);
        fallbackContainer = findViewById(R.id.aboutFallbackContainer);
        fallbackText = findViewById(R.id.aboutFallback);

        fallbackText.setMovementMethod(LinkMovementMethod.getInstance());
        fallbackText.setText(Html.fromHtml(
                getString(R.string.about_fallback), Html.FROM_HTML_MODE_COMPACT));

        webView.getSettings().setJavaScriptEnabled(false);
        webView.getSettings().setDomStorageEnabled(false);
        webView.setBackgroundColor(0x00000000);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showFallback();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, @NonNull WebResourceRequest request) {
                // Open external links in the browser, keep the about page itself in the WebView.
                String host = request.getUrl().getHost();
                if (host != null && host.equalsIgnoreCase("dezzk.github.io")) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                } catch (Exception ignored) {
                    return false;
                }
                return true;
            }
        });

        if (isOnline()) {
            webView.loadUrl(getString(R.string.about_url));
        } else {
            showFallback();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showFallback() {
        progress.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        fallbackContainer.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("about:blank");
        }
        super.onDestroy();
    }
}
