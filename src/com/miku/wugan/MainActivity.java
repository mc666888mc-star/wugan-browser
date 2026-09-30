package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 无感浏览器 v3：Edge 风格深色 UI。
 * 底部工具栏（后退/前进/地址 pill/标签数/菜单），全部功能收进底部弹出菜单。
 * v2 能力全部保留：自动点选服务、防验证 UA/cookie 策略、广告拦截、历史、无痕、
 * 嗅探+播放器。新增：多标签页、收藏夹、下载管理、桌面版 UA、页内查找、翻译、
 * 大声朗读、页面存档、添加到手机、设置页。
 */
public class MainActivity extends Activity {

    static final String PREFS = "wugan_prefs";
    static final String KEY_ADBLOCK = "adblock_enabled";
    static final String KEY_INCOGNITO = "incognito_global";
    static final String KEY_ADDR_TOP = "addr_bar_top";
    static final String KEY_ENGINE = "search_engine";
    private static final String ADBLOCK_PRIMARY =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String ADBLOCK_FALLBACK =
            "https://someonewhocares.org/hosts/zero/hosts";

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TAB_MAX = 10;
    private static final int REQUEST_WALLPAPER = 1001;

    private static final int SNIFF_MAX = 20;
    private static final String[] VIDEO_EXTS = {
            ".mp4", ".m3u8", ".webm", ".mov", ".flv", ".m4v"
    };

    /** 标签页模型：单 WebView 复用，切换时保存/恢复 url */
    private static class Tab {
        String url;
        String title;
        boolean incognito;
        Tab(String u, boolean inc) {
            url = u;
            incognito = inc;
        }
    }

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusView;
    private EditText addressPill;
    private TextView engineChip;
    private TextView tabsCount;
    private ImageView refreshButton;
    private LinearLayout findBar;
    private EditText findInput;
    private boolean addrBarTop = true;
    /** v8：页面是否正在加载（驱动刷新/停止二合一按钮） */
    private boolean pageLoading = false;

    private HistoryDbHelper historyDb;
    private BookmarkDbHelper bookmarkDb;
    private AdBlocker adBlocker;
    private SharedPreferences prefs;
    private String mobileUa;
    private boolean desktopMode = false;
    private TextToSpeech tts;
    private boolean ttsSpeaking = false;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private int curTab = 0;

    /** 嗅探到的视频地址（去重、按发现顺序、上限 20） */
    private final Set<String> sniffed =
            Collections.synchronizedSet(new LinkedHashSet<String>());
    private boolean showingSniff = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        addrBarTop = prefs.getBoolean(KEY_ADDR_TOP, true);
        applyToolbarPosition();
        historyDb = new HistoryDbHelper(this);
        bookmarkDb = new BookmarkDbHelper(this);
        adBlocker = new AdBlocker();
        adBlocker.setEnabled(prefs.getBoolean(KEY_ADBLOCK, true));
        initAdBlocker();

        progressBar = findViewById(R.id.progress_bar);
        statusView = findViewById(R.id.status_view);
        addressPill = findViewById(R.id.address_pill);
        tabsCount = findViewById(R.id.tabs_count);
        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);
        webView = findViewById(R.id.webview);

        setupWebView();
        setupBottomBar();
        setupFindBar();
        setupWallpaperLongPress();
        // 壁纸库种子（首次启动把默认壁纸拷入库），后台做
        new Thread(new Runnable() {
            @Override
            public void run() {
                WallpaperManager.ensureSeeded(MainActivity.this);
            }
        }).start();

        String startUrl = HOME_URL;
        Intent intent = getIntent();
        if (intent != null) {
            String extra = intent.getStringExtra("url");
            if (extra != null && !extra.isEmpty()) {
                startUrl = extra;
            }
        }
        tabs.add(new Tab(startUrl, false));
        curTab = 0;
        updateTabsButton();
        webView.loadUrl(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            loadInCurrentTab(url);
        }
    }

    /** v8：按偏好排两行。顶部模式=地址行在上、导航行在下；
     * 底部模式=地址行移到下方、叠在导航行上面。 */
    private void applyToolbarPosition() {
        LinearLayout root = findViewById(R.id.root_container);
        LinearLayout addressRow = findViewById(R.id.address_row);
        LinearLayout navRow = findViewById(R.id.nav_row);
        root.removeView(addressRow);
        root.removeView(navRow);
        if (addrBarTop) {
            root.addView(addressRow, 0);
            root.addView(navRow);
        } else {
            root.addView(addressRow);
            root.addView(navRow);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改了地址栏位置：重建 Activity 即时生效
        boolean top = prefs.getBoolean(KEY_ADDR_TOP, true);
        if (top != addrBarTop) {
            recreate();
            return;
        }
        if (webView != null) {
            webView.onResume();
        }
        // 设置页可能改了广告拦截开关，回来时同步
        adBlocker.setEnabled(prefs.getBoolean(KEY_ADBLOCK, true));
        // 设置页可能改了搜索引擎，回来时同步 chip
        refreshEngineChip();
    }

    // ================= v6：搜索引擎快捷切换 =================

    private static final String[] ENGINE_KEYS =
            {"bing", "yandex", "google", "duckduckgo"};
    private static final String[] ENGINE_NAMES =
            {"Bing", "Yandex", "Google", "DuckDuckGo"};
    private static final String[] ENGINE_SHORT =
            {"Bing", "Yandex", "谷歌", "Duck"};

    /** chip 显示当前引擎简称；设置页改了引擎回来时同步。 */
    private void refreshEngineChip() {
        if (engineChip == null) {
            return;
        }
        String cur = prefs.getString(KEY_ENGINE, "bing");
        int idx = 0;
        for (int i = 0; i < ENGINE_KEYS.length; i++) {
            if (ENGINE_KEYS[i].equals(cur)) {
                idx = i;
                break;
            }
        }
        engineChip.setText(ENGINE_SHORT[idx]);
    }

    /** 点 chip 弹菜单，四选一，当前项打勾。 */
    private void showEngineMenu() {
        String cur = prefs.getString(KEY_ENGINE, "bing");
        PopupMenu pm = new PopupMenu(
                new ContextThemeWrapper(this, android.R.style.Theme_Material),
                engineChip);
        for (int i = 0; i < ENGINE_KEYS.length; i++) {
            pm.getMenu().add(0, i, i, ENGINE_NAMES[i])
                    .setCheckable(true)
                    .setChecked(ENGINE_KEYS[i].equals(cur));
        }
        pm.getMenu().setGroupCheckable(0, true, true);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int i = item.getItemId();
                if (i >= 0 && i < ENGINE_KEYS.length) {
                    prefs.edit().putString(KEY_ENGINE, ENGINE_KEYS[i]).apply();
                    refreshEngineChip();
                    Toast.makeText(MainActivity.this,
                            "已切换为 " + ENGINE_NAMES[i],
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }
        });
        pm.show();
    }

    // ================= v8：地址行 + 底部导航行 =================

    private void setupBottomBar() {
        // v6：地址栏左侧搜索引擎快捷切换 chip
        engineChip = findViewById(R.id.engine_chip);
        engineChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showEngineMenu();
            }
        });
        refreshEngineChip();
        // v8：刷新/停止二合一按钮
        refreshButton = findViewById(R.id.refresh_button);
        updateRefreshButton();
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pageLoading) {
                    webView.stopLoading();
                    pageLoading = false;
                    updateRefreshButton();
                } else {
                    webView.reload();
                }
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
        // v8：底部导航行——主页 / 新标签页
        findViewById(R.id.home_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadInCurrentTab(HOME_URL);
            }
        });
        findViewById(R.id.newtab_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newTab(HOME_URL, false);
            }
        });
        addressPill.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // v8：点一下全选（post 到焦点稳定后执行，否则可能被系统覆盖）
                    addressPill.post(new Runnable() {
                        @Override public void run() {
                            addressPill.selectAll();
                        }
                    });
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(addressPill,
                                InputMethodManager.SHOW_IMPLICIT);
                    }
                } else {
                    hideKeyboard(addressPill);
                }
            }
        });
        // v9：只有第一次获得焦点时全选；已有焦点再点则正常放光标，方便局部编辑
        // （之前"每次点击都全选"太粗暴，已 revert）
        addressPill.setOnEditorActionListener(
                new TextView.OnEditorActionListener() {
                    @Override
                    public boolean onEditorAction(TextView v, int actionId,
                                                   KeyEvent event) {
                        boolean go = actionId == EditorInfo.IME_ACTION_GO
                                || (event != null
                                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.getAction() == KeyEvent.ACTION_DOWN);
                        if (go) {
                            String url = resolveInput(
                                    addressPill.getText().toString());
                            if (url != null) {
                                addressPill.clearFocus();
                                hideKeyboard(addressPill);
                                loadInCurrentTab(url);
                            }
                            return true;
                        }
                        return false;
                    }
                });
        findViewById(R.id.tabs_container).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTabsDialog();
            }
        });
        findViewById(R.id.menu_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenuDialog();
            }
        });
    }

    private void updateTabsButton() {
        tabsCount.setText(String.valueOf(tabs.size()));
    }

    /** v8：刷新/停止二合一按钮图标切换（加载中=✕停止，加载完=刷新）。 */
    private void updateRefreshButton() {
        if (refreshButton == null) {
            return;
        }
        refreshButton.setImageResource(
                pageLoading ? R.drawable.ic_close : R.drawable.ic_refresh);
        refreshButton.setContentDescription(pageLoading ? "停止加载" : "刷新");
    }

    /** v8：按 WebView 实际进度同步按钮（切 tab 后调用一次兜底）。 */
    private void syncRefreshButton() {
        if (webView != null) {
            pageLoading = webView.getProgress() < 100;
        }
        updateRefreshButton();
    }

    /**
     * v4：像网址（含点且无空格，或带 scheme）→ 直接加载；
     * 否则按设置里的默认搜索引擎搜索。
     */
    private String resolveInput(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (TextUtils.isEmpty(s)) {
            return null;
        }
        boolean hasScheme = s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
        boolean looksLikeUrl = hasScheme || (s.contains(".") && !s.contains(" "));
        if (looksLikeUrl) {
            return hasScheme ? s : "https://" + s;
        }
        String eng = prefs.getString(KEY_ENGINE, "bing");
        String base;
        if ("bing".equals(eng)) {
            base = "https://www.bing.com/search?q=";
        } else if ("duckduckgo".equals(eng)) {
            base = "https://duckduckgo.com/?q=";
        } else if ("yandex".equals(eng)) {
            base = "https://yandex.com/search/?text=";
        } else {
            base = "https://www.google.com/search?q=";
        }
        try {
            return base + URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return base + s;
        }
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ================= 多标签页 =================

    private void saveCurrentTab() {
        if (curTab >= 0 && curTab < tabs.size()) {
            Tab t = tabs.get(curTab);
            String u = webView.getUrl();
            if (u != null) {
                t.url = u;
            }
            String ti = webView.getTitle();
            if (ti != null) {
                t.title = ti;
            }
        }
    }

    private void newTab(String url, boolean incognito) {
        if (tabs.size() >= TAB_MAX) {
            Toast.makeText(this, "标签页已达上限(" + TAB_MAX + ")", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentTab();
        tabs.add(new Tab(url, incognito));
        curTab = tabs.size() - 1;
        updateTabsButton();
        webView.loadUrl(url);
        if (incognito) {
            Toast.makeText(this, "无痕标签页：关闭时清除痕迹", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchTab(int i) {
        if (i < 0 || i >= tabs.size() || i == curTab) {
            return;
        }
        saveCurrentTab();
        curTab = i;
        Tab t = tabs.get(i);
        updateTabsButton();
        webView.loadUrl(t.url != null ? t.url : HOME_URL);
        syncRefreshButton();
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) {
            return;
        }
        Tab t = tabs.remove(i);
        if (t.incognito) {
            // 无痕 tab：清掉它的 cookie 痕迹
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            webView.clearCache(true);
        }
        if (tabs.isEmpty()) {
            tabs.add(new Tab(HOME_URL, false));
            curTab = 0;
        } else if (curTab >= tabs.size()) {
            curTab = tabs.size() - 1;
        }
        updateTabsButton();
        Tab nt = tabs.get(curTab);
        webView.loadUrl(nt.url != null ? nt.url : HOME_URL);
        syncRefreshButton();
    }

    private void loadInCurrentTab(String url) {
        setStatus("");
        webView.loadUrl(url);
    }

    /** 标签列表 Dialog：标题+网址，点切换，每行 ✕ 关闭，底部"＋ 新标签页" */
    private void showTabsDialog() {
        saveCurrentTab();
        final Dialog d = new Dialog(this, R.style.BottomMenu);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            final Tab t = tabs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            row.setBackgroundResource(rippleRes());

            TextView tv = new TextView(this);
            String label = (t.incognito ? "[无痕] " : "")
                    + (t.title != null ? t.title : t.url)
                    + "\n" + (t.url != null ? t.url : "");
            tv.setText(label);
            tv.setTextColor(Color.parseColor(idx == curTab ? "#4DD0E1" : "#FFFFFF"));
            tv.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setSingleLine(false);
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv);

            ImageView close = new ImageView(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
            close.setLayoutParams(clp);
            close.setImageResource(R.drawable.ic_close);
            close.setPadding(dp(10), dp(10), dp(10), dp(10));
            close.setBackgroundResource(rippleRes());
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    closeTab(idx);
                    showTabsDialog();
                }
            });
            row.addView(close);

            final int fi = idx;
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    switchTab(fi);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    switchTab(fi);
                }
            });
            root.addView(row);

            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(Color.parseColor("#333333"));
            root.addView(div);
        }

        Button add = new Button(this);
        add.setText("＋ 新标签页");
        add.setTextColor(Color.parseColor("#FFFFFF"));
        add.setBackgroundResource(rippleRes());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, dp(8), 0, 0);
        add.setLayoutParams(alp);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                newTab(HOME_URL, false);
            }
        });
        root.addView(add);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================= 底部菜单 =================

    private void showMenuDialog() {
        final Dialog d = new Dialog(this, R.style.BottomMenu);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, dp(20));

        final boolean adblockOn = adBlocker.isEnabled();

        // A 行
        LinearLayout rowA = newRow();
        addMenuItem(rowA, R.drawable.ic_star, "收藏夹",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        startActivity(new Intent(MainActivity.this,
                                BookmarksActivity.class));
                    }
                });
        addMenuItem(rowA, R.drawable.ic_history, "历史记录",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        startActivity(new Intent(MainActivity.this,
                                HistoryActivity.class));
                    }
                });
        addMenuItem(rowA, R.drawable.ic_share, "共享",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        shareCurrent();
                    }
                });
        addMenuItem(rowA, R.drawable.ic_download, "下载",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        try {
                            startActivity(new Intent(
                                    DownloadManager.ACTION_VIEW_DOWNLOADS));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "打不开系统下载管理",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        addMenuItem(rowA, R.drawable.ic_settings, "设置",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        startActivity(new Intent(MainActivity.this,
                                SettingsActivity.class));
                    }
                });
        root.addView(rowA);
        root.addView(divider());

        // B 行
        LinearLayout rowB = newRow();
        addMenuItem(rowB, R.drawable.ic_bookmark_add, "添加到收藏夹",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        addBookmark();
                    }
                });
        addMenuItem(rowB, R.drawable.ic_desktop,
                desktopMode ? "桌面版·开" : "桌面版网站",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        toggleDesktopUa();
                    }
                });
        addMenuItem(rowB, R.drawable.ic_find, "页内查找",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        showFindBar();
                    }
                });
        addMenuItem(rowB, R.drawable.ic_settings, "设置",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        startActivity(new Intent(MainActivity.this,
                                SettingsActivity.class));
                    }
                });
        addMenuItem(rowB, R.drawable.ic_volume, "大声朗读",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        toggleTts();
                    }
                });
        root.addView(rowB);
        root.addView(divider());

        // C 行
        LinearLayout rowC = newRow();
        addMenuItem(rowC, R.drawable.ic_add, "新标签页",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        newTab(HOME_URL, false);
                    }
                });
        addMenuItem(rowC, R.drawable.ic_incognito, "无痕新标签页",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        newTab(HOME_URL, true);
                    }
                });
        addMenuItem(rowC, R.drawable.ic_block,
                adblockOn ? "广告拦截·开" : "广告拦截·关",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        toggleAdblock();
                    }
                });
        addMenuItem(rowC, R.drawable.ic_refresh, "更新规则",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        updateAdblockRules();
                    }
                });
        root.addView(rowC);

        LinearLayout rowD = newRow();
        addMenuItem(rowD, R.drawable.ic_file_download, "下载此页面",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        savePageArchive();
                    }
                });
        addMenuItem(rowD, R.drawable.ic_home_screen, "添加至手机",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        pinToHome();
                    }
                });
        addMenuItem(rowD, R.drawable.ic_logout, "退出浏览器",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        finishAffinity();
                    }
                });
        addMenuItem(rowD, R.drawable.ic_wallpaper, "更换壁纸",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        pickWallpaper();
                    }
                });
        root.addView(rowD);

        sv.addView(root);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(4f);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.setMargins(0, dp(8), 0, dp(8));
        v.setLayoutParams(lp);
        return v;
    }

    /** 系统涟漪背景资源 id（?attr/selectableItemBackground），代码里动态取 */
    private int rippleRes() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** 菜单格子：图标 + 文字，纵向排列，涟漪反馈 */
    private void addMenuItem(LinearLayout row, int iconRes, String label,
                             View.OnClickListener l) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, dp(12), 0, dp(12));
        item.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackgroundResource(rippleRes());
        item.setOnClickListener(l);

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iv.setLayoutParams(ilp);
        item.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#E8E8E8"));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(8), 0, 0);
        item.addView(tv);

        row.addView(item);
    }

    // ================= 菜单功能 =================

    private void shareCurrent() {
        String url = webView.getUrl();
        if (url == null) {
            return;
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, webView.getTitle());
        s.putExtra(Intent.EXTRA_TEXT, webView.getTitle() + "\n" + url);
        try {
            startActivity(Intent.createChooser(s, "共享链接"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void addBookmark() {
        String url = webView.getUrl();
        if (url == null || url.equals(HOME_URL)) {
            Toast.makeText(this, "当前页面不能收藏", Toast.LENGTH_SHORT).show();
            return;
        }
        bookmarkDb.add(url, webView.getTitle());
        Toast.makeText(this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
    }

    private void toggleDesktopUa() {
        desktopMode = !desktopMode;
        webView.getSettings().setUserAgentString(
                desktopMode ? DESKTOP_UA : mobileUa);
        webView.reload();
        Toast.makeText(this,
                desktopMode ? "已切换桌面版 UA（可能增加验证概率）" : "已切回移动版 UA",
                Toast.LENGTH_LONG).show();
    }

    private void toggleAdblock() {
        boolean on = !adBlocker.isEnabled();
        adBlocker.setEnabled(on);
        prefs.edit().putBoolean(KEY_ADBLOCK, on).apply();
        Toast.makeText(this, on ? "广告拦截开" : "广告拦截关",
                Toast.LENGTH_SHORT).show();
    }

    private void updateAdblockRules() {
        setStatus("正在更新广告规则…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = adBlocker.updateFromNetwork(MainActivity.this,
                        ADBLOCK_PRIMARY, ADBLOCK_FALLBACK);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("");
                        if (n >= 0) {
                            Toast.makeText(MainActivity.this,
                                    "广告规则已更新：" + n + " 条",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "更新失败，请检查网络后重试",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 下载此页面：saveWebArchive 存到应用私有下载目录 */
    private void savePageArchive() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            String name = "page-" + System.currentTimeMillis() + ".mht";
            File f = new File(dir, name);
            webView.saveWebArchive(f.getAbsolutePath());
            Toast.makeText(this, "已保存到：" + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 添加至手机桌面：固定快捷方式 */
    private void pinToHome() {
        String url = webView.getUrl();
        if (url == null || url.equals(HOME_URL)) {
            Toast.makeText(this, "先打开一个网页再添加", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm != null && sm.isRequestPinShortcutSupported()) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setAction(Intent.ACTION_VIEW);
                ShortcutInfo info = new ShortcutInfo.Builder(this,
                        "page-" + System.currentTimeMillis())
                        .setShortLabel(webView.getTitle() != null
                                ? webView.getTitle() : url)
                        .setLongLabel(url)
                        .setIcon(Icon.createWithResource(this,
                                R.mipmap.ic_launcher))
                        .setIntent(i)
                        .build();
                sm.requestPinShortcut(info, null);
            } else {
                Toast.makeText(this, "当前桌面不支持添加快捷方式",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "添加失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 壁纸 =================

    /** 主页应用壁纸：库空则不调用，home.html 用深色渐变兜底 */
    private void applyWallpaper() {
        final java.io.File f = WallpaperManager.current(this);
        if (f == null || !f.exists()) {
            return;
        }
        final String js = "setWallpaper('file://" + f.getAbsolutePath() + "')";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    /** 菜单"更换壁纸"：相册选图 */
    private void pickWallpaper() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(
                    Intent.createChooser(i, "选择壁纸"), REQUEST_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final java.io.File f =
                            WallpaperManager.addFromUri(MainActivity.this, uri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (f != null) {
                                Toast.makeText(MainActivity.this,
                                        "壁纸已更换", Toast.LENGTH_SHORT).show();
                                reloadHomeIfShowing();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "图片读取失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        }
    }

    /** 当前 tab 正在看主页 → 重载以应用新壁纸 */
    private void reloadHomeIfShowing() {
        String u = webView != null ? webView.getUrl() : null;
        if (HOME_URL.equals(u)) {
            webView.reload();
        }
    }

    /** 网页长按图片 → "设为壁纸" */
    private void setupWallpaperLongPress() {
        webView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult r = webView.getHitTestResult();
                if (r == null) {
                    return false;
                }
                int type = r.getType();
                if (type != WebView.HitTestResult.IMAGE_TYPE
                        && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    return false;
                }
                final String imgUrl = r.getExtra();
                if (imgUrl == null
                        || (!imgUrl.startsWith("http://")
                        && !imgUrl.startsWith("https://"))) {
                    return false;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("图片")
                        .setItems(new String[]{"设为壁纸"},
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d,
                                                        int which) {
                                        downloadWallpaper(imgUrl);
                                    }
                                })
                        .show();
                return true;
            }
        });
    }

    private void downloadWallpaper(final String imgUrl) {
        Toast.makeText(this, "正在下载壁纸…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = WallpaperManager.downloadFromUrl(
                        MainActivity.this, imgUrl);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err == null) {
                            Toast.makeText(MainActivity.this,
                                    "已设为壁纸", Toast.LENGTH_SHORT).show();
                            reloadHomeIfShowing();
                        } else {
                            Toast.makeText(MainActivity.this, err,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    // ================= 页内查找 =================

    private void setupFindBar() {
        findInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doFind();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.find_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doFind();
                webView.findNext(true);
            }
        });
        findViewById(R.id.find_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                webView.findNext(false);
            }
        });
        findViewById(R.id.find_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findBar.setVisibility(View.GONE);
                webView.clearMatches();
                hideKeyboard(findInput);
            }
        });
    }

    private void showFindBar() {
        findBar.setVisibility(View.VISIBLE);
        findInput.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void doFind() {
        String q = findInput.getText().toString();
        if (!TextUtils.isEmpty(q)) {
            webView.findAllAsync(q);
        }
    }

    // ================= 大声朗读 =================

    private void toggleTts() {
        if (ttsSpeaking && tts != null) {
            tts.stop();
            ttsSpeaking = false;
            Toast.makeText(this, "已停止朗读", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tts == null) {
            try {
                tts = new TextToSpeech(this,
                        new TextToSpeech.OnInitListener() {
                            @Override
                            public void onInit(int status) {
                                if (status == TextToSpeech.SUCCESS) {
                                    tts.setLanguage(Locale.CHINESE);
                                    tts.setOnUtteranceProgressListener(
                                            new UtteranceProgressListener() {
                                                @Override
                                                public void onStart(String id) {
                                                }
                                                @Override
                                                public void onDone(String id) {
                                                    ttsSpeaking = false;
                                                }
                                                @Override
                                                public void onError(String id) {
                                                    ttsSpeaking = false;
                                                }
                                            });
                                    speakPageText();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            "朗读初始化失败", Toast.LENGTH_SHORT).show();
                                    tts = null;
                                }
                            }
                        });
            } catch (Exception e) {
                Toast.makeText(this, "朗读初始化失败", Toast.LENGTH_SHORT).show();
                tts = null;
            }
        } else {
            speakPageText();
        }
    }

    /**
     * 单次 evaluateJavascript 取正文朗读（一次性调用，不常驻注入，
     * 不污染页面指纹）。
     */
    private void speakPageText() {
        if (tts == null) {
            return;
        }
        webView.evaluateJavascript("document.body.innerText.slice(0,20000)",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String text = unquoteJs(value).trim();
                        if (TextUtils.isEmpty(text)) {
                            Toast.makeText(MainActivity.this,
                                    "页面没有可朗读的文本", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ttsSpeaking = true;
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                                "wugan-tts");
                        Toast.makeText(MainActivity.this,
                                "开始朗读，再点一次停止", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private static String unquoteJs(String v) {
        if (v == null) {
            return "";
        }
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\n", "\n").replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    // ================= WebView 设置（含 v2 防验证策略） =================

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
        // 主页 file:// 页要加载 file:// 本地壁纸，显式允许文件访问
        s.setAllowFileAccess(true);

        // UA 加固：去掉暴露 WebView 身份的 "; wv" 标记，看起来像原生 Chrome Mobile。
        // 注意：保持移动端 UA，不伪装桌面端（UA 与 TLS 指纹表里不一反而更容易触发验证）。
        mobileUa = WebSettings.getDefaultUserAgent(this);
        if (mobileUa != null) {
            mobileUa = mobileUa.replace("; wv", "").replace(" Version/4.0", "");
            s.setUserAgentString(mobileUa);
        }

        // Cookie 全开并持久化：cf_clearance 留下来，同一站点二次访问直接放行。
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // 下载：交给系统 DownloadManager，通知栏可见
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    DownloadManager.Request req =
                            new DownloadManager.Request(Uri.parse(url));
                    req.setNotificationVisibility(
                            DownloadManager.Request
                                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = URLUtil.guessFileName(
                            url, contentDisposition, mimetype);
                    req.setTitle(name);
                    DownloadManager dm = (DownloadManager)
                            getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this,
                            "开始下载：" + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "下载失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                   WebResourceRequest request) {
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
                pageLoading = true;
                updateRefreshButton();
                // 页面加载完成才更新地址栏；正在打字（有焦点）时别打断
                if (!addressPill.hasFocus()) {
                    addressPill.setText(url != null ? url : "");
                }
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
                pageLoading = false;
                updateRefreshButton();
                saveCurrentTab();
                // 主页：应用壁纸（轮换开则每次取下一张）
                if (HOME_URL.equals(url)) {
                    applyWallpaper();
                }
                // 无痕（全局或当前 tab）不记录历史；验证页本身不记（噪音）
                if (!isIncognitoNow() && url != null && url.startsWith("http")
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
            public void onReceivedError(WebView view,
                                        WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    pageLoading = false;
                    updateRefreshButton();
                    setStatus("加载失败：" + error.getDescription());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(
                        newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    /** 当前是否处于无痕：全局开关 或 当前 tab 是无痕 tab */
    private boolean isIncognitoNow() {
        if (prefs.getBoolean(KEY_INCOGNITO, false)) {
            return true;
        }
        return curTab >= 0 && curTab < tabs.size()
                && tabs.get(curTab).incognito;
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

    private void setStatus(String text) {
        if (text == null || text.isEmpty()) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        }
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
        if (findBar.getVisibility() == View.VISIBLE) {
            findBar.setVisibility(View.GONE);
            webView.clearMatches();
            return;
        }
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
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (historyDb != null) {
            historyDb.close();
        }
        if (bookmarkDb != null) {
            bookmarkDb.close();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
