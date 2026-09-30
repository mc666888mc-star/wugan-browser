package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 无感浏览器主界面：系统 WebView 真内核，不做任何 JS 注入。
 *
 * v2 功能：
 * - 浏览历史（SQLite，无痕模式下不记录）
 * - 无痕模式（退出时清 Cookie/缓存/历史）
 * - 域名级广告拦截（AdBlocker，可开关、可在线更新规则）
 * - 视频资源嗅探（状态栏提示，点击进内置播放器）
 * 防验证思路不变：指纹保持一致（移动端 UA + 移动端 TLS，不伪装桌面）、
 * 持久化 Cookie（cf_clearance 复用，二次访问免验证）。
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://www.bing.com";
    private static final String PREFS = "wugan_prefs";
    private static final String KEY_ADBLOCK = "adblock_enabled";
    private static final String ADBLOCK_PRIMARY =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String ADBLOCK_FALLBACK =
            "https://someonewhocares.org/hosts/zero/hosts";

    private static final int SNIFF_MAX = 20;
    private static final String[] VIDEO_EXTS = {
            ".mp4", ".m3u8", ".webm", ".mov", ".flv", ".m4v"
    };

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private TextView statusView;
    private Button incognitoButton;
    private Button adblockButton;

    private HistoryDbHelper historyDb;
    private AdBlocker adBlocker;
    private SharedPreferences prefs;
    private boolean incognito = false;

    /** 嗅探到的视频地址（去重、按发现顺序、上限 20） */
    private final Set<String> sniffed =
            Collections.synchronizedSet(new LinkedHashSet<String>());
    private boolean showingSniff = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        historyDb = new HistoryDbHelper(this);
        adBlocker = new AdBlocker();
        adBlocker.setEnabled(prefs.getBoolean(KEY_ADBLOCK, true));
        initAdBlocker();

        addressBar = findViewById(R.id.address_bar);
        Button goButton = findViewById(R.id.go_button);
        progressBar = findViewById(R.id.progress_bar);
        statusView = findViewById(R.id.status_view);
        webView = findViewById(R.id.webview);
        incognitoButton = findViewById(R.id.incognito_button);
        adblockButton = findViewById(R.id.adblock_button);
        updateAdblockButton();

        setupWebView();

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadFromAddressBar();
            }
        });
        addressBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadFromAddressBar();
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) {
                    webView.goBack();
                }
            }
        });
        findViewById(R.id.forward_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoForward()) {
                    webView.goForward();
                }
            }
        });
        findViewById(R.id.history_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            }
        });
        incognitoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleIncognito();
            }
        });
        adblockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAdblock();
            }
        });
        findViewById(R.id.update_rules_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateAdblockRules();
            }
        });

        String startUrl = START_URL;
        Intent intent = getIntent();
        if (intent != null) {
            String extra = intent.getStringExtra("url");
            if (extra != null && !extra.isEmpty()) {
                startUrl = extra;
            }
        }
        webView.loadUrl(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            addressBar.setText(url);
            webView.loadUrl(url);
        }
    }

    /** 后台线程初始化广告规则（首次运行从 assets 拷贝），不阻塞界面 */
    private void initAdBlocker() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                adBlocker.init(MainActivity.this);
            }
        }).start();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        // UA 加固：去掉暴露 WebView 身份的 "; wv" 标记，看起来像原生 Chrome Mobile。
        // 注意：保持移动端 UA，不伪装桌面端（UA 与 TLS 指纹表里不一反而更容易触发验证）。
        String ua = WebSettings.getDefaultUserAgent(this);
        if (ua != null) {
            ua = ua.replace("; wv", "").replace(" Version/4.0", "");
            s.setUserAgentString(ua);
        }

        // Cookie 全开并持久化：cf_clearance 留下来，同一站点二次访问直接放行。
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                Uri uri = request.getUrl();
                // 1) 广告拦截优先：命中直接掐掉，不嗅探
                if (adBlocker.shouldBlock(uri)) {
                    return emptyResponse();
                }
                // 2) 被动嗅探视频资源（返回 null，WebView 正常处理）
                sniffVideoUrl(uri.toString());
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                addressBar.setText(url);
                // 新页面：清空嗅探结果，状态栏控制权交还
                synchronized (sniffed) {
                    sniffed.clear();
                }
                showingSniff = false;
                statusView.setOnClickListener(null);
                if (isChallengeUrl(url)) {
                    setStatus("Cloudflare 验证中，稍候…");
                } else {
                    setStatus("");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 无痕模式不记录历史；验证页本身不记（噪音）
                if (!incognito && url != null && url.startsWith("http")
                        && !isChallengeUrl(url)) {
                    historyDb.add(url, view.getTitle());
                }
                if (showingSniff) {
                    return; // 嗅探提示优先，不被覆盖
                }
                if (isChallengeUrl(url) || isChallengeTitle(view.getTitle())) {
                    setStatus("Cloudflare 验证中，稍候…");
                } else {
                    setStatus("");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    setStatus("加载失败：" + error.getDescription());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    /** 被拦截的广告请求：返回空 200 响应 */
    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", 200, "OK",
                Collections.<String, String>emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    /**
     * 被动嗅探视频直链：去掉 query/fragment 后按后缀判定。
     * 运行在 WebView 的网络线程，UI 更新一律 post 到主线程。
     */
    private void sniffVideoUrl(String url) {
        if (url == null) {
            return;
        }
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        u = u.toLowerCase(Locale.ROOT);
        boolean hit = false;
        for (String ext : VIDEO_EXTS) {
            if (u.endsWith(ext)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return;
        }
        boolean added;
        synchronized (sniffed) {
            added = sniffed.add(url);
            while (sniffed.size() > SNIFF_MAX) {
                sniffed.remove(sniffed.iterator().next()); // 删最早的
            }
        }
        if (added) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateSniffStatus();
                }
            });
        }
    }

    private void updateSniffStatus() {
        int n;
        synchronized (sniffed) {
            n = sniffed.size();
        }
        if (n == 0) {
            return;
        }
        showingSniff = true;
        setStatus("嗅探到视频(" + n + ")，点击播放");
        statusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSniffedDialog();
            }
        });
    }

    private void showSniffedDialog() {
        final List<String> list;
        synchronized (sniffed) {
            list = new ArrayList<String>(sniffed);
        }
        if (list.isEmpty()) {
            return;
        }
        if (list.size() == 1) {
            openPlayer(list.get(0));
            return;
        }
        final String[] items = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择要播放的视频")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openPlayer(items[which]);
                    }
                })
                .show();
    }

    private void openPlayer(String url) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", url);
        startActivity(i);
    }

    private void toggleIncognito() {
        incognito = !incognito;
        if (incognito) {
            incognitoButton.setText(R.string.incognito_on);
            setStatus("无痕模式：不记录历史，退出时清除 Cookie/缓存");
            Toast.makeText(this, "无痕模式开", Toast.LENGTH_SHORT).show();
        } else {
            incognitoButton.setText(R.string.incognito);
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            webView.clearCache(true);
            webView.clearHistory();
            setStatus("已退出无痕：Cookie/缓存/历史已清除");
            Toast.makeText(this, "已退出无痕并清除数据", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleAdblock() {
        boolean on = !adBlocker.isEnabled();
        adBlocker.setEnabled(on);
        prefs.edit().putBoolean(KEY_ADBLOCK, on).apply();
        updateAdblockButton();
        Toast.makeText(this, on ? "广告拦截开" : "广告拦截关", Toast.LENGTH_SHORT).show();
    }

    private void updateAdblockButton() {
        adblockButton.setText(adBlocker.isEnabled()
                ? R.string.adblock_on : R.string.adblock_off);
    }

    private void updateAdblockRules() {
        setStatus("正在更新广告规则…");
        adblockButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = adBlocker.updateFromNetwork(MainActivity.this,
                        ADBLOCK_PRIMARY, ADBLOCK_FALLBACK);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adblockButton.setEnabled(true);
                        setStatus("");
                        if (n >= 0) {
                            Toast.makeText(MainActivity.this,
                                    "广告规则已更新：" + n + " 条", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "更新失败，请检查网络后重试", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void loadFromAddressBar() {
        String input = addressBar.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            return;
        }
        String url = input;
        if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = "https://" + url;
        }
        hideKeyboard();
        setStatus("");
        webView.loadUrl(url);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        }
    }

    private void setStatus(String text) {
        statusView.setText(text == null ? "" : text);
    }

    private static boolean isChallengeUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("challenges.cloudflare.com")
                || url.contains("/cdn-cgi/challenge");
    }

    private static boolean isChallengeTitle(String title) {
        if (title == null) {
            return false;
        }
        String t = title.toLowerCase();
        return t.contains("just a moment")
                || t.contains("verifying you are human")
                || t.contains("请稍候")
                || t.contains("验证");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        // 落盘 Cookie，cf_clearance 下次还在
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (historyDb != null) {
            historyDb.close();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
