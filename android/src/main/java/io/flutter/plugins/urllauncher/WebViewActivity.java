// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.urllauncher;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.Browser;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/*  Launches WebView activity */
public class WebViewActivity extends Activity {

    /*
     * Use this to trigger a BroadcastReceiver inside WebViewActivity
     * that will request the current instance to finish.
     * */
    public static String ACTION_CLOSE = "close action";

    private final BroadcastReceiver broadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_CLOSE.equals(action)) {
                        finish();
                    }
                }
            };

    private final WebViewClient webViewClient = new WebViewClient() {


        /*
         * This method is deprecated in API 24. Still overridden to support
         * earlier Android versions.
         */
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                view.loadUrl(url);
                return false;
            }
            return super.shouldOverrideUrlLoading(view, url);
        }

        @RequiresApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String mUrl = request.getUrl().toString();
                if (mUrl.startsWith("http")) {
                    view.loadUrl(mUrl);
                    return true;
                } else if (mUrl.startsWith("weixin://") || mUrl.startsWith("alipays://") || mUrl.startsWith("mailto://") || mUrl.startsWith("tel://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mUrl));
                        startActivity(intent);
                        return false;
                    } catch (Exception e) {
                        return  false;
                    }
                }
            }
            return true;
        }
    };

    private WebView webview;

    private TextView mTitle;

    private IntentFilter closeIntentFilter = new IntentFilter(ACTION_CLOSE);

    // Verifies that a url opened by `Window.open` has a secure url.
    private class FlutterWebChromeClient extends WebChromeClient {

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            mTitle.setText(title);
        }

        @Override
        public boolean onCreateWindow(
                final WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebViewClient webViewClient =
                    new WebViewClient() {
                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        @Override
                        public boolean shouldOverrideUrlLoading(
                                @NonNull WebView view, @NonNull WebResourceRequest request) {
                            webview.loadUrl(request.getUrl().toString());
                            return true;
                        }

                        /*
                         * This method is deprecated in API 24. Still overridden to support
                         * earlier Android versions.
                         */
                        @SuppressWarnings("deprecation")
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, String url) {
                            webview.loadUrl(url);
                            return true;
                        }
                    };

            final WebView newWebView = new WebView(webview.getContext());
            newWebView.setWebViewClient(webViewClient);

            final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();

            return true;
        }
    }

    private  class MyWebViewDownLoadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.web_view_activity);

        mTitle = findViewById(R.id.tv_title);

        webview = findViewById(R.id.wv_main);

        findViewById(R.id.btn_complete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Get the Intent that started this activity and extract the string
        final Intent intent = getIntent();
        final String url = intent.getStringExtra(URL_EXTRA);
        final boolean enableJavaScript = intent.getBooleanExtra(ENABLE_JS_EXTRA, false);
        final boolean enableDomStorage = intent.getBooleanExtra(ENABLE_DOM_EXTRA, false);
        final Bundle headersBundle = intent.getBundleExtra(Browser.EXTRA_HEADERS);

        final Map<String, String> headersMap = extractHeaders(headersBundle);
        webview.loadUrl(url, headersMap);

        webview.getSettings().setJavaScriptEnabled(enableJavaScript);
        webview.getSettings().setDomStorageEnabled(enableDomStorage);

        // Open new urls inside the webview itself.
        webview.setWebViewClient(webViewClient);

        // Multi windows is set with FlutterWebChromeClient by default to handle internal bug: b/159892679.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {

            webview.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        }
        webview.getSettings().setSupportMultipleWindows(true);
        webview.setWebChromeClient(new FlutterWebChromeClient());
        webview.setDownloadListener(new MyWebViewDownLoadListener());

        // Register receiver that may finish this Activity.
        registerReceiver(broadcastReceiver, closeIntentFilter);
    }

    @VisibleForTesting
    public static Map<String, String> extractHeaders(@Nullable Bundle headersBundle) {
        if (headersBundle == null) {
            return Collections.emptyMap();
        }
        final Map<String, String> headersMap = new HashMap<>();
        for (String key : headersBundle.keySet()) {
            final String value = headersBundle.getString(key);
            headersMap.put(key, value);
        }
        return headersMap;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webview.clearCache(true);
        webview.clearHistory();
        unregisterReceiver(broadcastReceiver);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webview.canGoBack()) {
            webview.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String URL_EXTRA = "url";
    private static String ENABLE_JS_EXTRA = "enableJavaScript";
    private static String ENABLE_DOM_EXTRA = "enableDomStorage";

    /* Hides the constants used to forward data to the Activity instance. */
    public static Intent createIntent(
            Context context,
            String url,
            boolean enableJavaScript,
            boolean enableDomStorage,
            Bundle headersBundle) {
        return new Intent(context, WebViewActivity.class)
                .putExtra(URL_EXTRA, url)
                .putExtra(ENABLE_JS_EXTRA, enableJavaScript)
                .putExtra(ENABLE_DOM_EXTRA, enableDomStorage)
                .putExtra(Browser.EXTRA_HEADERS, headersBundle);
    }
}
