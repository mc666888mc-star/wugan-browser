package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * v5：Edge 风格分组设置页。
 * 外观和布局 / 搜索引擎 / 壁纸 / 隐私和安全 / 无障碍 / 设为默认浏览器 / 关于。
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "wugan_prefs";
    static final String KEY_ADBLOCK = "adblock_enabled";
    static final String KEY_INCOGNITO = "incognito_global";
    static final String KEY_ADDR_TOP = "addr_bar_top";
    static final String KEY_ENGINE = "search_engine";

    private static final String[] ENGINES = {"bing", "yandex", "google", "duckduckgo"};
    private static final String[] ENGINE_NAMES = {"Bing", "Yandex", "Google", "DuckDuckGo"};
    private static final String ADBLOCK_PRIMARY =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String ADBLOCK_FALLBACK =
            "https://someonewhocares.org/hosts/zero/hosts";

    private static final int REQUEST_WALLPAPER = 1001;

    private SharedPreferences prefs;
    private HistoryDbHelper historyDb;
    private TextView a11yStatus;
    private TextView addrPosValue;
    private TextView engineValue;
    private TextView wallpaperCountValue;
    private TextView batterySub;
    private Switch rotateSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        historyDb = new HistoryDbHelper(this);
        a11yStatus = findViewById(R.id.a11y_status);
        addrPosValue = findViewById(R.id.addr_pos_value);
        engineValue = findViewById(R.id.engine_value);
        wallpaperCountValue = findViewById(R.id.wallpaper_count_value);
        batterySub = findViewById(R.id.battery_sub);

        // ---- 外观和布局：地址栏位置 ----
        findViewById(R.id.addr_pos_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddrPosDialog();
            }
        });

        // ---- 搜索引擎 ----
        findViewById(R.id.engine_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEngineDialog();
            }
        });

        // ---- 壁纸 ----
        findViewById(R.id.wallpaper_pick_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickWallpaper();
            }
        });
        rotateSwitch = findViewById(R.id.wallpaper_rotate_switch);
        rotateSwitch.setChecked(WallpaperManager.isRotate(this));
        rotateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                WallpaperManager.setRotate(SettingsActivity.this, isChecked);
                Toast.makeText(SettingsActivity.this,
                        isChecked ? getString(R.string.rotate_on) : getString(R.string.rotate_off),
                        Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.wallpaper_manage_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this,
                        WallpaperActivity.class));
            }
        });

        // ---- 隐私和安全 ----
        Switch adblockSwitch = findViewById(R.id.adblock_switch);
        adblockSwitch.setChecked(prefs.getBoolean(KEY_ADBLOCK, true));
        adblockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_ADBLOCK, isChecked).apply();
                Toast.makeText(SettingsActivity.this,
                        isChecked ? getString(R.string.adblock_on_toast) : getString(R.string.adblock_off_toast),
                        Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rules_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateRules();
            }
        });
        findViewById(R.id.clear_data_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearBrowsingData();
            }
        });
        Switch incognitoSwitch = findViewById(R.id.incognito_switch);
        incognitoSwitch.setChecked(prefs.getBoolean(KEY_INCOGNITO, false));
        incognitoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_INCOGNITO, isChecked).apply();
                if (!isChecked) {
                    // 退出无痕：清 Cookie/缓存/历史（与 v2 逻辑一致）
                    CookieManager cm = CookieManager.getInstance();
                    cm.removeAllCookies(null);
                    cm.flush();
                    historyDb.clear();
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.incognito_exited), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.incognito_on_toast), Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ---- 无障碍 ----
        Button a11yButton = findViewById(R.id.a11y_button);
        a11yButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        // 点无障碍行弹出诊断（系统名单原文 + 服务心跳），方便排查"开了却显示未开启"
        findViewById(R.id.a11y_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showA11yDiag();
            }
        });
        // v11.3：revert v10.6/v11.1/v11.2 的指引弹窗——实测该机型点无障碍开关
        // 直接就能开，不存在"受限制的设置"这一关，指引是错的，直接删掉，
        // "应用信息"点开即直达系统页面
        findViewById(R.id.appinfo_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        findViewById(R.id.battery_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // v10.7：直接弹系统"允许后台运行"对话框，点一下就行，
                // 不用再去电池优化大列表里翻找本应用
                try {
                    Intent i = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e2) {
                        Toast.makeText(SettingsActivity.this,
                                getString(R.string.cant_open_settings),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        findViewById(R.id.taptest_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(SettingsActivity.this, MainActivity.class);
                i.putExtra("url", "file:///android_asset/taptest.html");
                startActivity(i);
            }
        });

        // ---- 设为默认浏览器 ----
        findViewById(R.id.default_browser_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.cant_open_settings), Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ---- 关于 ----
        TextView about = findViewById(R.id.about_text);
        about.setText(getString(R.string.about_text, appVersion()));

        refreshSettingsValues();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v10.4 三态：未开启 / 开了但没跑起来 / 运行中
        boolean inList = isInSecureList();
        boolean running = isInRunningList();
        if (running) {
            a11yStatus.setText(getString(R.string.a11y_on));
        } else if (inList) {
            a11yStatus.setText(getString(R.string.a11y_on_not_running));
        } else {
            a11yStatus.setText(getString(R.string.a11y_off_tap));
        }
        refreshSettingsValues();
    }

    private void refreshSettingsValues() {
        boolean top = prefs.getBoolean(KEY_ADDR_TOP, true);
        addrPosValue.setText(top ? getString(R.string.addr_top) : getString(R.string.addr_bottom));
        String eng = prefs.getString(KEY_ENGINE, "bing");
        String name = "Google";
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(eng)) {
                name = ENGINE_NAMES[i];
                break;
            }
        }
        engineValue.setText(name + " ›");
        int n = WallpaperManager.list(this).length;
        wallpaperCountValue.setText(getString(R.string.wallpaper_count, n));
        // v10.7：电池优化白名单状态（回来自动刷新，onResume 会调这里）
        if (batterySub != null) {
            boolean ignoring = false;
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                ignoring = pm != null
                        && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                ignoring = false;
            }
            batterySub.setText(getString(ignoring
                    ? R.string.battery_allowed : R.string.battery_not_allowed));
        }
        if (rotateSwitch != null) {
            rotateSwitch.setChecked(WallpaperManager.isRotate(this));
        }
    }

    /** 关于页显示的版本号：读 manifest，免得每次发版忘改文案。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void showAddrPosDialog() {
        final boolean top = prefs.getBoolean(KEY_ADDR_TOP, true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.addr_pos_title)
                .setSingleChoiceItems(new String[]{getString(R.string.addr_bottom_opt), getString(R.string.addr_top_opt)},
                        top ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.edit().putBoolean(KEY_ADDR_TOP,
                                        which == 1).apply();
                                refreshSettingsValues();
                                d.dismiss();
                                Toast.makeText(SettingsActivity.this,
                                        getString(R.string.take_effect_hint),
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    private void showEngineDialog() {
        String cur = prefs.getString(KEY_ENGINE, "bing");
        int checked = 0;
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(cur)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.default_engine)
                .setSingleChoiceItems(ENGINE_NAMES, checked,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.edit().putString(KEY_ENGINE,
                                        ENGINES[which]).apply();
                                refreshSettingsValues();
                                d.dismiss();
                            }
                        })
                .show();
    }

    /** 更换壁纸：相册选图 */
    private void pickWallpaper() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.pick_wallpaper)), REQUEST_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.gallery_unavailable), Toast.LENGTH_SHORT).show();
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
                    final File f = WallpaperManager.addFromUri(
                            SettingsActivity.this, uri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SettingsActivity.this,
                                    f != null ? getString(R.string.wallpaper_changed) : getString(R.string.image_read_failed),
                                    Toast.LENGTH_SHORT).show();
                            refreshSettingsValues();
                        }
                    });
                }
            }).start();
        }
    }

    /** 更新广告规则：写共享规则文件，返回浏览器后生效 */
    private void updateRules() {
        Toast.makeText(this, getString(R.string.updating_rules), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = new AdBlocker().updateFromNetwork(
                        SettingsActivity.this,
                        ADBLOCK_PRIMARY, ADBLOCK_FALLBACK);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (n >= 0) {
                            Toast.makeText(SettingsActivity.this,
                                    getString(R.string.rules_updated, n),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(SettingsActivity.this,
                                    getString(R.string.update_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void clearBrowsingData() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        historyDb.clear();
        Toast.makeText(this, getString(R.string.cleared_data),
                Toast.LENGTH_SHORT).show();
    }

    /** 本服务的 flatten 名：com.miku.wugan/com.miku.wugan.ChallengeTapService */
    private String myFlattenName() {
        return new ComponentName(this, ChallengeTapService.class).flattenToString();
    }

    /** 信号一：系统 Settings.Secure 名单里有没有我 */
    private boolean isInSecureList() {
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        if (enabled != 1) {
            return false;
        }
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) {
            return false;
        }
        // 系统存的是 flatten 后的完整类名，用 ComponentName 逐个比对；
        // 之前用 "/.ChallengeTapService" 短名 contains 永远匹配不上（v8.5 修过）
        String me = myFlattenName();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (me.equals(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    /** 信号二：AccessibilityManager 正在运行的服务名单里有没有我（防某些 ROM 名单写法怪异） */
    private boolean isInRunningList() {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            String me = myFlattenName();
            List<AccessibilityServiceInfo> running = am
                    .getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (running == null) {
                return false;
            }
            for (AccessibilityServiceInfo info : running) {
                if (me.equals(info.getId())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 无障碍诊断对话框：把系统看到的原始状态都摆出来，截图发开发者就能定位 */
    private void showA11yDiag() {
        int master = 0;
        try {
            master = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Exception ignored) {
        }
        String raw = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        SharedPreferences ap = getSharedPreferences(
                ChallengeTapService.A11Y_PREFS, MODE_PRIVATE);
        String alive = ChallengeTapService.formatTime(
                ap.getLong(ChallengeTapService.KEY_LAST_CONNECT, 0));
        String unbind = ChallengeTapService.formatTime(
                ap.getLong(ChallengeTapService.KEY_LAST_UNBIND, 0));

        String yes = getString(R.string.a11y_diag_yes);
        String no = getString(R.string.a11y_diag_no);
        String never = getString(R.string.a11y_diag_never);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.a11y_diag_master)).append(": ")
                .append(master == 1 ? yes : no).append("\n");
        sb.append(getString(R.string.a11y_diag_in_list)).append(": ")
                .append(isInSecureList() ? yes : no).append("\n");
        sb.append(getString(R.string.a11y_diag_running)).append(": ")
                .append(isInRunningList() ? yes : no).append("\n");
        sb.append(getString(R.string.a11y_diag_alive)).append(": ")
                .append(alive != null ? alive : never).append("\n");
        sb.append(getString(R.string.a11y_diag_unbind)).append(": ")
                .append(unbind != null ? unbind : never).append("\n");
        // v10.2 遥测：挂件有没有被看到、点选有没有发生、点中没有
        String widgetSeen = ChallengeTapService.formatTime(
                ap.getLong(ChallengeTapService.KEY_LAST_WIDGET_SEEN, 0));
        long tapTime = ap.getLong(ChallengeTapService.KEY_LAST_TAP_TIME, 0);
        boolean tapOk = ap.getBoolean(ChallengeTapService.KEY_LAST_TAP_OK, false);
        sb.append(getString(R.string.a11y_diag_widget)).append(": ")
                .append(widgetSeen != null ? widgetSeen : never).append("\n");
        sb.append(getString(R.string.a11y_diag_tap)).append(": ");
        if (tapTime > 0) {
            sb.append(ChallengeTapService.formatTime(tapTime))
                    .append(tapOk ? getString(R.string.a11y_diag_ok)
                            : getString(R.string.a11y_diag_fail));
        } else {
            sb.append(never);
        }
        sb.append("\n");
        sb.append(getString(R.string.a11y_diag_raw)).append(":\n")
                .append(raw != null ? raw : "-");
        // v10.4：开了但没跑起来时，直接在诊断框里给人话指引
        if (isInSecureList() && !isInRunningList()) {
            sb.append(getString(R.string.a11y_diag_hint_not_running));
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.a11y_diag_title)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (historyDb != null) {
            historyDb.close();
        }
        super.onDestroy();
    }
}
