package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.io.InputStream;

/**
 * v5：Edge 风格分组设置页。
 * 外观和布局 / 搜索引擎 / 壁纸 / 隐私和安全 / VPN / 设为默认浏览器 / 关于。
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
    private static final int REQUEST_VPN_AUTH = 1002;

    private SharedPreferences prefs;
    private HistoryDbHelper historyDb;
    private TextView addrPosValue;
    private TextView engineValue;
    private TextView wallpaperCountValue;
    private TextView batterySub;
    private TextView vpnStatus;
    private Button vpnButton;
    private Switch rotateSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        historyDb = new HistoryDbHelper(this);
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
        // ---- VPN（内置）：一键注册普通账号并连接 ----
        vpnStatus = findViewById(R.id.vpn_status);
        vpnButton = findViewById(R.id.vpn_button);
        vpnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVpn();
            }
        });
        refreshVpnStatus();

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
        findViewById(R.id.licenses_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLicenses();
            }
        });

        refreshSettingsValues();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVpnStatus();
        refreshSettingsValues();
    }

    /** VPN 状态行：已连接 / 未连接 */
    private void refreshVpnStatus() {
        if (vpnStatus == null || vpnButton == null) {
            return;
        }
        if (VpnManager.isOn()) {
            vpnStatus.setText(getString(R.string.vpn_on));
            vpnButton.setText(getString(R.string.vpn_disconnect));
        } else {
            vpnStatus.setText(getString(R.string.vpn_off));
            vpnButton.setText(getString(R.string.vpn_connect));
        }
        vpnButton.setEnabled(true);
    }

    /** 一键开启 / 断开。注册在后台线程做，起服务可能需要系统 VPN 授权 */
    private void toggleVpn() {
        if (VpnManager.isOn()) {
            VpnManager.disconnect(this);
            refreshVpnStatus();
            return;
        }
        vpnButton.setEnabled(false);
        vpnStatus.setText(getString(R.string.vpn_registering));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                if (!VpnManager.isRegistered(SettingsActivity.this)) {
                    err = VpnManager.register(SettingsActivity.this);
                }
                if (err == null) {
                    vpnStatus.post(new Runnable() {
                        @Override
                        public void run() {
                            vpnStatus.setText(
                                    getString(R.string.vpn_connecting));
                        }
                    });
                    err = VpnManager.startService(SettingsActivity.this);
                }
                final String msg = err;
                vpnStatus.post(new Runnable() {
                    @Override
                    public void run() {
                        if (msg == null) {
                            refreshVpnStatus();
                        } else if (VpnManager.NEED_AUTH.equals(msg)) {
                            // 系统 VPN 授权：弹系统对话框，点了允许再起服务
                            Intent auth = VpnManager.authIntent(
                                    SettingsActivity.this);
                            if (auth != null) {
                                startActivityForResult(auth,
                                        REQUEST_VPN_AUTH);
                            } else {
                                vpnButton.setEnabled(true);
                                vpnStatus.setText(getString(R.string.vpn_off));
                            }
                        } else {
                            vpnButton.setEnabled(true);
                            vpnStatus.setText(getString(R.string.vpn_off));
                            Toast.makeText(SettingsActivity.this,
                                    getString(R.string.vpn_conn_fail) + " (" + msg + ")",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 开源许可：内置隧道二进制文件的 MIT 许可文本（协议要求保留署名，放这里不打扰主界面） */
    private void showLicenses() {
        String text = "";
        InputStream in = null;
        try {
            in = getAssets().open("notices.txt");
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while ((n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            text = new String(buf, 0, off, "UTF-8");
        } catch (Exception ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.licenses_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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
        // v13.0：系统 VPN 授权回调。点了允许 → prepare 已通过，直接起服务；
        // 拒绝 → toast 提示，按钮恢复。
        if (requestCode == REQUEST_VPN_AUTH) {
            if (resultCode == RESULT_OK) {
                String err = VpnManager.startService(this);
                if (err != null) {
                    Toast.makeText(this,
                            getString(R.string.vpn_conn_fail) + " (" + err + ")",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.vpn_auth_denied),
                        Toast.LENGTH_LONG).show();
            }
            vpnButton.setEnabled(true);
            refreshVpnStatus();
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

    @Override
    protected void onDestroy() {
        if (historyDb != null) {
            historyDb.close();
        }
        super.onDestroy();
    }
}
