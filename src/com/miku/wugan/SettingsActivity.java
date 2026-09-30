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

import java.io.File;

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
        boolean on = isA11yServiceOn();
        a11yStatus.setText(on ? getString(R.string.a11y_on) : getString(R.string.a11y_off));
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
