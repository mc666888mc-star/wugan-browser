package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：广告拦截开关、无痕模式开关、清除浏览数据、
 * 无障碍服务状态 + 一键跳转、关于。
 */
public class SettingsActivity extends Activity {

    static final String PREFS = "wugan_prefs";
    static final String KEY_ADBLOCK = "adblock_enabled";
    static final String KEY_INCOGNITO = "incognito_global";
    static final String KEY_ADDR_TOP = "addr_bar_top";
    static final String KEY_ENGINE = "search_engine";

    private static final String[] ENGINES = {"google", "bing", "duckduckgo", "yandex"};
    private static final String[] ENGINE_NAMES = {"Google", "Bing", "DuckDuckGo", "Yandex"};

    private SharedPreferences prefs;
    private HistoryDbHelper historyDb;
    private TextView a11yStatus;
    private TextView addrPosValue;
    private TextView engineValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        historyDb = new HistoryDbHelper(this);
        a11yStatus = findViewById(R.id.a11y_status);

        Switch adblockSwitch = findViewById(R.id.adblock_switch);
        adblockSwitch.setChecked(prefs.getBoolean(KEY_ADBLOCK, true));
        adblockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(KEY_ADBLOCK, isChecked).apply();
                Toast.makeText(SettingsActivity.this,
                        isChecked ? "广告拦截开" : "广告拦截关",
                        Toast.LENGTH_SHORT).show();
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
                            "已退出无痕并清除数据", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(SettingsActivity.this,
                            "无痕模式开：不记录历史", Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.clear_data_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.flush();
                historyDb.clear();
                Toast.makeText(SettingsActivity.this,
                        "已清除 Cookie 与浏览历史", Toast.LENGTH_SHORT).show();
            }
        });

        Button a11yButton = findViewById(R.id.a11y_button);
        a11yButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        TextView about = findViewById(R.id.about_text);
        about.setText("无感浏览器 v4.0\n"
                + "系统 WebView 真内核 · 零 JS 注入 · GPLv3 开源\n"
                + "自动点选 Cloudflare 验证，省去等待和点勾的时间\n"
                + "图标：Material Icons by Google（Apache License 2.0）");

        addrPosValue = findViewById(R.id.addr_pos_value);
        engineValue = findViewById(R.id.engine_value);
        refreshSettingsValues();

        findViewById(R.id.addr_pos_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddrPosDialog();
            }
        });
        findViewById(R.id.engine_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEngineDialog();
            }
        });
    }

    private void refreshSettingsValues() {
        boolean top = prefs.getBoolean(KEY_ADDR_TOP, false);
        addrPosValue.setText(top ? "顶部 ›" : "底部 ›");
        String eng = prefs.getString(KEY_ENGINE, "google");
        String name = "Google";
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(eng)) {
                name = ENGINE_NAMES[i];
                break;
            }
        }
        engineValue.setText(name + " ›");
    }

    private void showAddrPosDialog() {
        final boolean top = prefs.getBoolean(KEY_ADDR_TOP, false);
        new AlertDialog.Builder(this)
                .setTitle("地址栏位置")
                .setSingleChoiceItems(new String[]{"底部", "顶部"},
                        top ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.edit().putBoolean(KEY_ADDR_TOP,
                                        which == 1).apply();
                                refreshSettingsValues();
                                d.dismiss();
                                Toast.makeText(SettingsActivity.this,
                                        "返回浏览器后即时生效",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    private void showEngineDialog() {
        String cur = prefs.getString(KEY_ENGINE, "google");
        int checked = 0;
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(cur)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("默认搜索引擎")
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

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isA11yServiceOn();
        a11yStatus.setText(on ? "状态：已开启 ✓" : "状态：未开启（自动点选不工作）");
    }

    /** 无障碍服务是否已启用：系统开关开 + 本服务在已启用列表里 */
    private boolean isA11yServiceOn() {
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
        return services != null
                && services.contains(getPackageName() + "/.ChallengeTapService");
    }

    @Override
    protected void onDestroy() {
        if (historyDb != null) {
            historyDb.close();
        }
        super.onDestroy();
    }
}
