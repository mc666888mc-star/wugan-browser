# 无感浏览器

基于系统 WebView 的真内核浏览器 + 无障碍自动点选，目标是**让 Cloudflare 验证尽量少弹、弹了也自动点掉**，省去手动等待和点勾的时间。

包名：`com.miku.wugan` ｜ minSdk 26 ｜ targetSdk 34 ｜ 零第三方依赖（手工构建链，见 `build.sh`）
当前版本：v2.0（versionCode 2）

## 它做了什么

1. **真浏览器内核**：用系统 WebView（就是 Chrome 内核），不是自动化工具。Cloudflare 眼里是正常手机浏览器。
2. **UA 去 WebView 标记**：把 UA 里的 `; wv` 去掉，看起来像原生 Chrome Mobile。**保持移动端 UA，不伪装桌面**（UA 和 TLS 指纹表里不一反而更容易被拦）。
3. **Cookie 持久化**：`cf_clearance`（验证通过凭证）落盘保存，同一站点二次访问直接放行，不用重复验证。
4. **自动点选**：`ChallengeTapService`（无障碍服务）检测到验证页里的复选框会自动点一下，15 秒内只点一次，切窗口重置。v2 扩展了关键词，能识别内嵌式 Turnstile 挂件（如 dash.cloudflare.com 注册页的"请验证您是真人"）。
5. **零 JS 注入**：页面里不注入任何脚本（注入脚本本身就是可检测的指纹）。
6. **浏览历史**：SQLite 记录访问历史（时间+标题+网址），工具栏「历史」查看，点击回到浏览器打开，一键清空。
7. **无痕模式**：工具栏「无痕」开关。开启后不记录历史；关闭退出时清除 Cookie/缓存/网页历史。注意：这是"退出即清"的会话级无痕。
8. **域名级广告拦截**：`AdBlocker` 在 `shouldInterceptRequest` 按域名拦截（精确+子域名后缀匹配），内置 StevenBlack 完整规则（约 7.4 万条），默认开启，可一键开关。工具栏「更新规则」从网络下载最新规则（主源 StevenBlack，备用 someonewhocares），更新后 Toast 显示条数。
9. **视频嗅探 + 内置播放器**：被动嗅探页面中的直链视频（.mp4/.m3u8/.webm/.mov/.flv/.m4v），状态栏提示"嗅探到视频(n)，点击播放"，点击进内置播放器（VideoView+MediaController，原生支持 HLS），右上角一键横竖屏切换。

## 诚实说明（先看完再装）

- **做不到"一次都不弹"**：弹不弹是 Cloudflare 服务端决定的，权重排序是 IP 信誉 > 站点安全等级 > 指纹一致性。这个 App 只优化第 3 项。
- **IP 仍是老大**：走机房/代理 IP，该弹还是弹；手机流量直连基本不弹。
- **自动点选是启发式的**：认"复选框点勾"型 Turnstile（含内嵌挂件）；纯倒计时 5 秒那种（"Just a moment" 自动转圈）不需要点，等它自己过；极端情况（Under Attack Mode）谁来都得等。
- **广告拦截是域名级的**：只拦请求，不做元素隐藏/CSS 注入，所以广告位可能留白块——这是故意的，为了不污染页面指纹。
- **无障碍权限要手动开**：安装后去「设置 → 无障碍 → 无感浏览器自动点选」打开开关，否则自动点选不工作（浏览器本身不受影响）。

## 安装

1. 把 `无感浏览器.apk` 传到手机，点安装（允许"安装未知应用"）。v2 与 v1 同签名，可直接覆盖安装。
2. 打开 App，地址栏输网址，「进入」。
3. （可选）设置 → 无障碍 → 开启「无感浏览器自动点选」。

## 构建

```bash
cd ~/workspace/stealth-browser
bash build.sh
```

要求：JDK 17（`build.sh` 已写死 PATH）、`~/Android/Sdk`（platforms/android-34 + build-tools/34.0.0）。
签名 keystore 在项目根 `debug.keystore`（常驻，不进 `out/`，保证覆盖安装不报签名冲突）。
广告规则源文件在 `assets/adblock_hosts.txt`（构建时打进 APK，首次运行拷贝到应用私有目录后使用）。

## 开源协议

GPLv3（见 `LICENSE` 文件）。

## v2 更新日志

- 自动点选关键词大扩展：覆盖内嵌式 Turnstile（"请验证您是真人"/"turnstile"/"我不是机器人"等），复选框判定放宽（文本命中或"验证页+CheckBox 类名"）
- 新增浏览历史（SQLite，去重，一键清空）
- 新增无痕模式（退出即清 Cookie/缓存/历史）
- 新增域名级广告拦截（7.4 万条内置规则，可开关，可在线更新）
- 新增视频嗅探 + 内置播放器（m3u8/HLS，横竖屏切换）
- 应用图标：`res/mipmap-xxxhdpi/ic_launcher.png`
