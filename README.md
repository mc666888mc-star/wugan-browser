# 无感浏览器 · Wugan Browser

[中文版](#中文版) ｜ [English](#english-version)

> 纯净无感的真内核浏览器，内置 VPN 一键连接加密隧道：稳定指纹 + 复用验证凭证，原生访问外网。
> A clean, real-kernel browser with built-in VPN: one-tap encrypted tunnel, consistent fingerprint, reused clearance cookies, native access to the open internet.

- 包名 / Package：`com.miku.wugan` ｜ minSdk 26 ｜ targetSdk 34 ｜ 零第三方依赖 / zero third-party dependencies
- 当前版本 / Current version：v12.1（versionCode 29）
- 开源协议 / License：GPLv3

---

## 中文版

### 它做了什么

1. **真浏览器内核**：用系统 WebView（就是 Chrome 内核），不是自动化工具。Cloudflare 眼里是正常手机浏览器。
2. **UA 去 WebView 标记**：把 UA 里的 `; wv` 去掉，看起来像原生 Chrome Mobile。**保持移动端 UA，不伪装桌面**（UA 和 TLS 指纹表里不一反而更容易被拦）。
3. **Cookie 持久化**：`cf_clearance`（验证通过凭证）落盘保存，同一站点二次访问直接放行，不用重复验证。
4. **内置 VPN**：设置里「VPN（内置）」一键注册普通账号并连接，浏览器流量经加密隧道直达外网。免 root，不碰系统 VPN 设置；下载器同样走隧道。
5. **零 JS 注入**：页面里不注入任何脚本（注入脚本本身就是可检测的指纹）。
6. **Edge 式双栏工具栏**（v8）：顶部地址行 = 搜索引擎胶囊 + 加宽输入框 + 刷新/停止二合一按钮（加载中显示 ✕ 点停，加载完变刷新）；底部导航行 = 后退 / 前进 / 主页 / 新标签 / 标签页数 / ⋯菜单。
7. **浏览历史 + 收藏夹**：SQLite 记录，点击打开，一键清空；网址列表里 %XX 编码会解码成中文显示（v8.2）。
8. **无痕模式**：开启后不记录历史；退出时清除 Cookie/缓存/网页历史（会话级无痕）。
9. **域名级广告拦截**：`shouldInterceptRequest` 按域名拦截（精确 + 子域名后缀匹配），内置 StevenBlack 完整规则（约 7.4 万条），默认开启，可一键开关、在线更新。
10. **视频嗅探 + 内置播放器**：被动嗅探页面中的直链视频（.mp4/.m3u8/.webm/.mov/.flv/.m4v），状态栏提示"点击播放"，进内置播放器（VideoView + MediaController，原生支持 HLS），一键横竖屏；v10 加了双击快进/快退 10 秒、倍速（0.5x~2x）、屏幕锁定。
11. **本地壁纸主页**：全离线壁纸库，可从相册选图、长按网页图片设为壁纸，支持轮换（v5/v7）。
12. **中英双语**（v8.4）：英文系统全英文显示（Wugan Browser），中文系统不受影响，其他语言默认回退中文。
13. **内置下载器**（v9）：多线程断点续传，下载页有进度条 + 实时速度 + 剩余时间，可暂停/继续/取消；支持直链 m3u8（分片自动合并）。播放器里有「下载视频」按钮。

⋯ 菜单：收藏夹 / 历史 / 共享 / 下载 / 设置 / 添加到收藏夹 / 桌面版网站 / 页内查找 / 大声朗读 / 新标签页 / 无痕新标签页 / 广告拦截开关 / 更新规则 / 下载此页面 / 添加至手机 / 退出浏览器 / 更换壁纸

### 🎬 关于内置播放器：为什么我们选择"克制"？

很多用户可能会问：作为一个浏览器，为什么内置的视频播放器看起来这么"简陋"？没有弹幕、没有投屏、也不能后台悬浮播放？

其实，这是刻意为之。在对抗 Cloudflare 等高级 WAF 的场景下，"少即是多"不仅是一种美学，更是生存法则。

1. **被动嗅探，绝不主动出击**：我们不会去强行解析网页 DOM，也不会尝试破解加密流。只有当网页自己正常加载了直链（如 `.mp4` 或 `.m3u8`）时，我们才会在底层默默捕获。不增加任何额外的网络请求，完美隐身，绝不触发风控。
2. **坚守原生，拒绝"巨无霸"**：我们只使用了 Android 系统原生的 `VideoView` + `MediaController`。没有引入动辄几 MB 的第三方解码库，没有申请 `SYSTEM_ALERT_WINDOW`（悬浮窗）等敏感权限。它加载、播放、退出，绝不后台驻留，不收集任何播放数据。
3. **守住边界，不越俎代庖**：我们清楚自己的定位。我们只是一个帮你把网页里掉落的视频顺手播放的工具，而不是一个臃肿的短视频平台。

真正的强大，不是无所不能，而是清楚地知道什么*不该做*。保持轻量，保持无感。

### 诚实说明（先看完再装）

- **做不到"一次都不弹"**：弹不弹是 Cloudflare 服务端决定的，权重排序是 IP 信誉 > 站点安全等级 > 指纹一致性。这个 App 只优化第 3 项。
- **IP 仍是老大**：走机房/代理 IP，该弹还是弹；手机流量直连基本不弹。
- **自动点选已砍掉**（v12.0）：Turnstile 多数为非交互式、自己转圈就过，之前也没有一次被用户确认的成功点选。留出最纯净的壳子，验证页请手动点一下。
- **内置 VPN 的边界**：只接管浏览器的 http/https **GET** 请求和下载器；POST 请求（如登录表单提交）和内置播放器的视频流暂走直连。隧道本身是用户态实现，速度不如系统级 VPN，够用但别指望跑满。
- **广告拦截是域名级的**：只拦请求，不做元素隐藏/CSS 注入，所以广告位可能留白块——这是故意的，为了不污染页面指纹。

### 安装

1. 把 `无感浏览器.apk` 传到手机，点安装（允许"安装未知应用"）。同签名，可直接覆盖安装。
2. 打开 App，地址栏输网址，回车。
3. （可选）设置 → VPN（内置）→ 一键开启，浏览器即获得加密隧道。

### 构建

```bash
cd ~/workspace/stealth-browser
bash build.sh
```

要求：JDK 17（`build.sh` 已写死 PATH）、`~/Android/Sdk`（platforms/android-34 + build-tools/34.0.0）。
内置 VPN 引擎需先编好 `~/workspace/usque-aar/usque.aar`（gomobile 把隧道引擎编成 AAR，`build.sh`
会自动取出其中的 classes.jar 与 arm64 `.so` 打进 APK）。
签名 keystore 在项目根 `debug.keystore`（常驻，不进 `out/`，保证覆盖安装不报签名冲突）。
广告规则源文件在 `assets/adblock_hosts.txt`（构建时打进 APK，首次运行拷贝到应用私有目录后使用）。

### 开源协议

GPLv3（见 `LICENSE` 文件）。

图标素材：Material Icons by Google，Apache License 2.0（`res/drawable-xxxhdpi/` 下的 `ic_*.png`，Round 风格，原图为黑色，填充为白色后收录）。

### 更新日志

#### v12.1（VPN 引擎换血：exec 改 JNI）

- **v12.0 的 exec 方案在真机上没跑起来**：`ProcessBuilder` 起外置二进制直接报 `Cannot run program`，
  注册一步就失败。实测确认，改走已验证的路子。
- **新引擎**：gomobile 把隧道引擎编译进 APK，进程内 JNI 调用，一次 exec 都没有——注册、
  建隧道、127.0.0.1:1080 开 SOCKS5 全在进程内完成。设置页 UI 不变（一键开启/断开）。
- 连接时做**端到端 SOCKS 探针**：经隧道真连一次 1.1.1.1:443，走完全程才算连上；
  QUIC 不通自动改 HTTP2（TCP）再试。
- MIT 署名见 `NOTICES.md` 与设置页「关于 → 开源许可」。

#### v12.0（砍掉自动点选 + 内置 VPN）

- **砍掉自动点选全部代码**：无障碍服务、诊断框、测试页、设置项全部删除，留出最纯净的壳子（之前没有一次被用户确认的成功点选，Turnstile 多数自过）。
- **内置 VPN**：设置页新增「VPN（内置）」专区，一键注册普通账号并连接；浏览器 http/https 流量与下载器经加密隧道直达外网。免 root，不碰系统 VPN 设置。
- 产品定位更新：从"减少验证打扰"改为"纯净浏览 + 内置 VPN"。
- 内置隧道组件为 MIT 许可（v12.0 是外置二进制形式，v12.1 起改为 JNI 编进 APK），署名见 `NOTICES.md` 与设置页「关于 → 开源许可」。

#### v11.3（删掉多余的指引弹窗）

- 「应用信息」点开直接跳系统页面，不再弹指引框；无障碍/电池优化直达不变

#### v11.0（播放器新界面 + 画中画）

- 新布局：左上视频标题（页面标题优先，取不到就显示文件名）+ 右侧竖排圆形按钮
- 圆钮：播放/暂停、倍速、锁定、画中画、旋转、下载——5 个图标（播放/暂停/锁/画中画/旋转）全部手绘
- 画中画：点圆钮进小窗，后台继续播；小窗里自动藏掉所有按钮
- 锁定逻辑同步新布局；横屏照常用

#### v10.8（下载通知）

- 下载有了系统通知：进度条（1s/3% 节流，不轰炸通知栏）/ 完成点开直接看文件 / 失败点进去重试 / 暂停显示"已暂停"
- 补声明 `POST_NOTIFICATIONS`；Android 13+ 第一次点下载时就地申请一次，拒绝也不纠缠——下载照常用，只是不弹通知

#### v10.7（默认浏览器身份 + 电池一键允许）

- 修"系统默认浏览器列表读不出我们"：根因是 Manifest 里没声明浏览器身份（VIEW + BROWSABLE + http/https）。现在声明了，会出现在系统默认浏览器候选里；外部链接点我们也能正确接住并打开（MainActivity 改 singleTask，复用窗口不叠罗汉）
- 「电池优化」改直达授权：不再跳那个翻半天找不到的大列表，直接弹系统对话框"允许后台运行？"，点一下允许就行；设置页实时显示"已允许 ✓ / 还没允许"，回来自动刷新
- 补声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限（就是上面那个对话框要的）；下载器不发系统通知，`POST_NOTIFICATIONS` 不需要，没加

#### v10.5（首页居中搜索 + 标签页预览图）

- 首页加了居中搜索框：大 Logo + 圆角搜索条，输关键词或网址回车即达（走 `wugan://search` 交给 App 解析，沿用地址栏同一套"像网址直达、否则按默认引擎搜"，零 JS 桥接）
- 标签页列表支持预览图：每行左侧加页面缩略图（页面加载完自动抓拍），一眼认出是哪个页；关闭标签页时顺手回收图片不漏内存
- 图标说明：App 里用的本来就是 Material Icons（Google 开源，Apache 2.0），和"GitHub 开源现代图标库"说的是同一类，无需换

#### v10.4（点选测试靶场 + 一键直达设置）

- 新增「🧪 自动点选测试」：内置确定性测试页，用 Cloudflare 官方强制交互测试 key，每次必定弹出勾选框——几秒就知道点选灵不灵，不用再去注册页碰运气、录屏
- 设置页无障碍状态改三态：「未开启 / 已开启 ✓ / 已开启但系统没把它跑起来 ⚠️」，开了没跑时直接给人话指引（诊断框里也加了同一段提示）
- 新增一键直达：「应用信息」、「电池优化」（允许后台活动，防服务被杀）；无障碍直达按钮本来就有

#### v10.3（下载器修文件名/打开/重命名）

- APK 下载不再变 `.bin`：根因是 WebView 传过来的真文件名（`Content-Disposition`）和 MIME 被 `enqueue()` 直接扔掉、只拿 URL 猜名字。现在全接住，Worker 还拿响应头再纠正一次（`Content-Disposition` → URL 路径 → `Content-Type` 补后缀）
- 下载完能正常在别的 App 打开：之前 MIME 跟着错成 `octet-stream`，装 APK 也认不出；精确 MIME 打不开时自动退到 `*/*` 让用户选
- 每行右侧加 ⋮ 菜单：打开 / 在文件管理器中打开 / 重命名 / 删除，暂停/继续/重试/取消都收进菜单
- 「在文件管理器中打开」直达 `Download/无感浏览器` 文件夹；支持重命名（不写后缀自动保留原来的）
- 附带修了 API 26-28 上"打开"必失败的坑：`file://` 会被系统拦，新增零依赖的 `SimpleFileProvider` 对外分享

#### v10.2（挂件级自动点选）

- 自动点选加第二条独立路径：不再只问"整页是不是验证页"，直接找"验证标签 + 复选框"成对出现的验证挂件（专治注册页里那种内嵌式 Turnstile）。老路径原封不动，整页验证的效果不受影响
- 只在挂件容器里挑最优的点：类名是 CheckBox 的优先，已勾选的不碰，容器外的复选框（如"订阅邮件"）不会误点
- 诊断框加遥测：最后一次看到挂件的时间、最后一次点选的时间与成败，截图一眼定案

#### v10.1（无障碍诊断）

- 自动点选不工作排查：服务加存活心跳（SharedPreferences 记录最后一次连接/断开时间）；设置页点"无障碍"行弹出诊断框（总开关/系统名单/运行中名单/心跳/原始名单），截图就能定位是"没开上"还是"开了没存活"
- 状态检测改双信号：系统名单 或 运行中服务名单命中任一即算已开启（防某些 ROM 名单写法怪异）
- 事件类型加 `typeWindowStateChanged`，挑战页整窗切换时也能抓到

#### v10.0（播放器升级）

- 双击屏幕左/右半边：快退/快进 10 秒，中间弹出 -10s/+10s 提示
- 倍速按钮：0.5x → 1.0x → 1.25x → 1.5x → 2.0x 循环切换（按钮上直接显示当前倍速）
- 锁定按钮：锁住后隐藏所有按钮和控制条，只留一个「解锁」，防手滑误触

#### v9.0（内置下载器）

- 新下载引擎 `Downloader`：多线程（3 并发）+ 断点续传（`Range` 请求续写）+ 500ms 一次速度采样、2 秒滑窗算实时速度、ETA 剩余时间估算
- 内置下载器页面：进度条 + 当前速度 + 剩余时间，支持暂停/继续/取消/重试；下载完可打开、也可删除文件
- 普通文件（mp4、apk、zip 等）+ 直链 m3u8（下载分片自动合并成一个 ts 文件）；加密视频流（`EXT-X-KEY`）会明确提示不支持
- 播放器右上角新增「下载视频」按钮；网页里的下载改走内置下载器（不再跳系统下载界面）
- 下载保存到手机 Download 文件夹；新启动时崩溃中断的任务标为「已暂停」，可手动继续
- 宣传措辞收敛：tagline 改为「尽量减少验证打扰」，如实说明机制（稳定指纹 + 复用验证凭证 + 复选框式自动点选）

#### v8.5（两个 bug 修复）

- 设置页无障碍状态永远显示"未开启"：之前用 `包名/.ChallengeTapService` 短名去匹配系统存的完整 flatten 类名，永远匹配不上；改成 ComponentName 逐个比对
- Cloudflare 注册页等内嵌 Turnstile 点不动：挂件把"请验证您是真人"写在可点 wrapper 的子节点上，可点节点自己没文本；改成子树（限深 4 层）文本也算命中，且先深后浅优先点最里层的可点节点。顺带让 Yandex 第一关的复选框也能被点到

- 142 条文案抽成 `res/values/strings.xml`（中文默认）+ `res/values-en/strings.xml`（英文）；英文系统全英文显示，中文系统不受影响，其他语言默认回退中文
- 带参数文案用占位符（`%1$s` / `%1$d`）；搜索引擎 chip 的"谷歌"按语言显示 Google/谷歌
- 不动：`ChallengeTapService` 检测关键词（功能性文本）、Log 日志中文

#### v8.3

- 修"Cloudflare 验证中"在正常页面（GitHub 等）误弹：之前靠标题含"验证"判断，标题常是上一页旧标题；改成只认验证页网址

#### v8.2

- 历史/收藏列表的网址解码显示（`%E5%AE%B9` 这类不再像乱码），超长截断；只改显示，不改存的数据

#### v8.1

- 地址栏只有第一次点击才全选，再点正常放光标（之前"每次点都全选"太粗暴，已 revert）

#### v8（Edge 式双栏工具栏）

- 顶部地址行 = [引擎 chip][加宽输入框][刷新/停止二合一]；底部导航行 = [后退][前进][主页][新标签 +][标签页数][⋯菜单]
- 刷新/停止二合一：加载中显示 ✕（点停），加载完变刷新图标
- 新增主页按钮；地址栏位置设置保留（顶部默认/底部）；关于页版本号改为读取 versionName

#### v7

- 主页去掉中间大卡片，纯壁纸

#### v6

- ⋯ 菜单"翻译"删除（国内网络连不上谷歌翻译），换回"设置"
- 地址栏头部搜索引擎快捷切换胶囊（Bing / Yandex / Google / DuckDuckGo，Bing 置顶，新安装默认 Bing）

#### v5

- 地址栏直输（EditText，点一下全选+弹键盘，回车跳转）
- 主页壁纸模式（全本地/离线）：壁纸库、相册选图、长按网页图设为壁纸、轮换、壁纸管理
- 设置页 Edge 化（外观和布局 / 搜索引擎 / 壁纸 / 隐私和安全 / 无障碍 / 设为默认浏览器 / 关于）

#### v4

- 24 个图标统一换 Google Material Icons（Round 白版）；UI 去生硬（圆角/间距/涟漪反馈）
- 地址栏位置可设置（顶部/底部）；默认搜索引擎可换（Google / Bing / DuckDuckGo / Yandex）

#### v3

- Edge 风格 UI：底部工具栏 + slide-up 菜单；多标签页（上限 10）；收藏夹；系统 DownloadManager 下载；桌面版 UA 切换；页内查找；TTS 朗读；设置页

#### v2

- 自动点选关键词扩展（内嵌式 Turnstile）；浏览历史；无痕模式；域名级广告拦截（StevenBlack 7.4 万条）；视频嗅探 + 内置播放器；定制幽灵图标

---

## English Version

### What it does

1. **Real browser core**: built on the system WebView (i.e. Chrome) — not automation tooling. To Cloudflare it looks like an ordinary mobile browser.
2. **De-WebView'd UA**: strips the `; wv` token so it reads as stock Chrome Mobile. **Stays mobile — no desktop spoofing** (a desktop UA over a mobile TLS fingerprint is *more* suspicious).
3. **Persistent cookies**: `cf_clearance` is saved to disk, so repeat visits to the same site sail through without re-verification.
4. **Auto-tap**: `ChallengeTapService` (an AccessibilityService) taps verification checkboxes for you — at most once per 15 s, reset on window change. v2 expanded the keyword list to catch embedded Turnstile widgets.
5. **Zero JS injection**: nothing is ever injected into pages (injected scripts are themselves a detectable fingerprint).
6. **Edge-style dual toolbar** (v8): top address row = search-engine chip + wide input + refresh/stop combo button (✕ while loading, refresh when done); bottom nav row = back / forward / home / new tab / tab count / ⋯ menu.
7. **History + bookmarks**: SQLite-backed, tap to reopen, one-tap clear; percent-encoded URLs are decoded for display (v8.2).
8. **Incognito**: no history while on; cookies/cache/history wiped on exit (session-level).
9. **Domain-level ad blocking**: blocked in `shouldInterceptRequest` (exact + subdomain-suffix matching), bundled with the full StevenBlack list (~74k rules), on by default, one-tap toggle, updatable online.
10. **Video sniffing + built-in player** (v10: double-tap to skip ±10 s, speed 0.5x–2x, screen lock): passively sniffs direct video links (.mp4/.m3u8/.webm/.mov/.flv/.m4v), status-bar prompt to play, built-in player (VideoView + MediaController, native HLS), one-tap landscape/portrait.
11. **Local wallpaper home**: fully offline wallpaper library — pick from gallery, long-press any web image to set as wallpaper, rotation supported (v5/v7).
12. **Bilingual** (v8.4): full English UI on English-system devices (as "Wugan Browser"); Chinese elsewhere.
13. **Built-in downloader** (v9): multi-threaded with resume; downloads page shows progress bar + live speed + ETA, with pause/resume/cancel; direct m3u8 links get their segments auto-merged. The player has a "Download video" button.

⋯ menu: Bookmarks / History / Share / Downloads / Settings / Add to bookmarks / Desktop site / Find in page / Read aloud / New tab / New incognito tab / Ad blocker toggle / Update rules / Save page / Add to home screen / Exit / Change wallpaper

### 🎬 About the Built-in Player: Why We Chose "Restraint"

You might wonder why a browser's built-in video player looks so… minimalist. No danmaku, no casting, no floating window?

It's strictly by design. When your whole product is about staying under the radar of advanced WAFs like Cloudflare, "less is more" isn't just an aesthetic — it's a survival rule.

1. **Passive sniffing, never active probing.** We don't parse the DOM or crack encrypted streams. We only pick up direct links (`.mp4`, `.m3u8`, …) that the page itself loads naturally — silently, at the network layer. Zero extra requests, zero new fingerprints, zero reason to trip anti-bot systems.
2. **Stock Android, no bloat.** Just `VideoView` + `MediaController` from the OS. No multi-megabyte third-party decoder SDKs, no sensitive permissions like `SYSTEM_ALERT_WINDOW`. It loads, it plays, it exits. No background lingering, no playback telemetry.
3. **Know your place.** We're a handy tool that plays a video that happened to fall out of a webpage — not a bloated streaming platform.

True power isn't being able to do everything; it's knowing exactly what *not* to do. Stay lightweight, stay imperceptible.

### Please read before installing

- **It can't guarantee zero challenges.** Whether a challenge appears is decided by Cloudflare's servers. Rough priority: IP reputation > site security level > fingerprint consistency. This app only optimizes the third.
- **IP still rules.** On datacenter/proxy IPs you'll still get challenged; on direct mobile data you mostly won't.
- **Auto-tap is heuristic.** It handles checkbox-style Turnstile (including embedded widgets); pure countdown challenges ("Just a moment" spinners) need no tapping — just wait; extreme cases (Under Attack Mode) make everyone wait.
- **Ad blocking is domain-level.** Requests are blocked, elements aren't hidden — so ad slots may show as blank boxes. Deliberate: no page-fingerprint pollution.
- **Enable the accessibility service manually**: Settings → Accessibility → turn on "Wugan Browser auto-tap". The browser works fine without it; only auto-tap needs it.

### Install

1. Copy `无感浏览器.apk` to your phone and install (allow "install unknown apps"). Same signature across versions — installs right over the old one.
2. Open the app, type a URL in the address bar, hit enter.
3. (Optional) Settings → Accessibility → enable "Wugan Browser auto-tap".

### Build

```bash
cd ~/workspace/stealth-browser
bash build.sh
```

Requires: JDK 17 (`build.sh` hardcodes the PATH), `~/Android/Sdk` (platforms/android-34 + build-tools/34.0.0).
The signing keystore lives at the project root as `debug.keystore` (kept out of `out/` so re-signing never breaks overlay installs).
Ad-block rules source: `assets/adblock_hosts.txt` (packed into the APK, copied to the app's private dir on first run).

### License

GPLv3 (see `LICENSE`).

Icons: Material Icons by Google, Apache License 2.0 (the `ic_*.png` files under `res/drawable-xxxhdpi/`, Round style, recolored white from black originals).

### Changelog

#### v11.3 (removed the extra guide dialog)

- "App info" now jumps straight to the system page, no more guide popup; accessibility/battery shortcuts unchanged

#### v11.0 (new player UI + picture-in-picture)

- New layout: video title top-left (page title first, file name as fallback) + vertical round buttons on the right
- Round buttons: play/pause, speed, lock, PiP, rotate, download — 5 icons (play/pause/lock/PiP/rotate) all hand-drawn
- Picture-in-picture: tap to shrink into a floating window, keeps playing; controls auto-hide in PiP
- Lock logic follows the new layout; landscape works as before

#### v10.8 (download notifications)

- Downloads now post system notifications: progress bar (throttled to 1s/3% so it doesn't spam) / tap a finished one to open the file / tap a failed one to retry / paused shows "paused"
- Declared `POST_NOTIFICATIONS`; on Android 13+ it's requested once in context at first download — decline and downloads still work, just silently

#### v10.7 (default-browser identity + one-tap battery allow)

- Fixed "system default-browser list doesn't show us": the manifest never declared browser identity (VIEW + BROWSABLE + http/https). It's declared now, so we appear in the system's default-browser candidates; tapping a link elsewhere opens it in our window too (MainActivity is now singleTask — reuses the window instead of stacking)
- "Battery optimization" now goes straight to authorization: no more hunting through the giant list — a system dialog asks "allow background activity?", one tap and done; the settings page shows live status ("allowed ✓ / not yet") and refreshes on return
- Declared `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (required for that dialog); the downloader posts no system notifications so `POST_NOTIFICATIONS` wasn't added

#### v10.5 (centered home search + tab thumbnails)

- Centered search box on the home page: big logo + rounded search bar, type a keyword or URL and hit enter (goes through `wugan://search` and reuses the address bar's "URL goes direct, otherwise search with the default engine" logic — no JS bridge)
- Tab list now shows thumbnails: each row gets a page snapshot on the left (captured automatically when a page finishes loading) so you can tell tabs apart at a glance; bitmaps are recycled when a tab closes
- Icon note: the app already uses Material Icons (open-source by Google, Apache 2.0) — exactly the kind of "open-source modern icon library" suggested, so no swap needed

#### v10.4 (tap-test range + settings shortcuts)

- New "🧪 Auto-tap test": a built-in deterministic test page using Cloudflare's official force-interactive test key, so the checkbox appears every single time — know in seconds whether tapping works, no more sign-up-page roulette or screen recordings
- Accessibility status is now three-state: off / on ✓ / on-but-not-running ⚠️, with plain-language guidance when the system lists the service but won't run it (same hint added to the diagnostics dialog)
- New one-tap shortcuts: "App info" and "Battery optimization" (allow background activity so the system doesn't kill the service); the accessibility shortcut already existed

#### v10.3 (downloader filename/open/rename fixes)

- APK downloads no longer become `.bin`: the real filename (`Content-Disposition`) and MIME type handed over by WebView were being dropped and the name was guessed from the URL alone. They are now honored, and the worker double-checks against response headers (`Content-Disposition` → URL path → `Content-Type` extension fallback)
- Finished downloads now open properly in other apps (MIME used to be wrongly `octet-stream`, so even APK installers rejected them); falls back to `*/*` so the user can pick when no app handles the exact type
- Each row gets a ⋮ menu on the right: Open / Open in file manager / Rename / Delete, with Pause/Resume/Retry/Cancel inside
- "Open in file manager" jumps straight to the `Download/无感浏览器` folder; rename supported (original extension kept if you don't type one)
- Also fixed "Open" always failing on API 26-28: `file://` URIs are blocked by the system, so a dependency-free `SimpleFileProvider` was added for sharing

#### v10.2 (widget-level auto-tap)

- Auto-tap gains a second, independent path: instead of only asking "is this a challenge page", it now looks for a verification widget where a label ("Verify you are human" etc.) and a checkbox appear together — built for embedded Turnstile widgets like the one on sign-up pages. The old path is untouched, so full-page challenge handling is unaffected
- Taps only the best candidate inside the widget container (CheckBox class preferred, checked boxes skipped); checkboxes outside the widget (e.g. marketing opt-ins) are never touched
- Diagnostics dialog now shows telemetry: when a widget was last seen and when the last tap was attempted and whether it succeeded

#### v10.1 (accessibility diagnostics)

- Auto-tap troubleshooting: the service now writes a heartbeat (last connect/unbind timestamps); tapping the Accessibility row in Settings opens a diagnostics dialog (master switch, system list, running list, heartbeat, raw list) — a screenshot is enough to tell "not enabled" from "enabled but dead"
- Status check now uses two signals: enabled if found in either the system list or the running-services list
- Added `typeWindowStateChanged` so whole-window challenge transitions are caught too

#### v10.0 (player upgrade)

- Double-tap left/right half of the screen to skip back/forward 10 s, with a -10s/+10s indicator
- Speed button cycles 0.5x → 1.0x → 1.25x → 1.5x → 2.0x (current speed shown on the button)
- Lock button hides all controls, leaving only "Unlock" — no more accidental touches

#### v9.0 (built-in downloader)

- New download engine `Downloader`: 3-thread pool, resumable downloads (Range requests), 500 ms speed sampling with a 2 s sliding window and ETA estimation
- New downloads page: progress bar + live speed + ETA, with pause/resume/cancel/retry; finished files can be opened or deleted
- Regular files (mp4, apk, zip, …) and direct m3u8 links (segments auto-merged into one ts file); encrypted streams (`EXT-X-KEY`) fail with an explicit "not supported" message
- New "Download video" button in the player; in-page downloads now go through the built-in downloader instead of the system download UI
- Files land in the phone's Download folder; tasks interrupted by a crash are marked "Paused" on next launch and can be resumed
- Marketing copy toned down: the tagline now says "minimize interruptions", describing the real mechanisms (consistent fingerprint, reused clearance cookies, checkbox auto-tap)

#### v8.5 (two bug fixes)

- Settings always showed accessibility as "Off": the old check matched the short name `pkg/.ChallengeTapService` against the system's stored fully-flattened component names, which never matches; now compares via ComponentName one by one
- Embedded Turnstile (e.g. Cloudflare sign-up page) never got tapped: the widget puts "verify you are human" text on a child of the clickable wrapper, leaving the clickable node itself textless; subtree text (depth-limited to 4) now counts as a hit, and deepest clickable nodes are tried first. Yandex's first-stage checkbox benefits too

- 142 strings extracted into `res/values/strings.xml` (Chinese default) + `res/values-en/strings.xml` (English); full English UI on English-system devices as "Wugan Browser"; everything else falls back to Chinese
- Parameterized strings use `%1$s` / `%1$d`; the engine chip shows Google/谷歌 per locale
- Untouched: `ChallengeTapService` detection keywords (functional, not UI), Chinese log messages

#### v8.3

- Fixed false "Verifying with Cloudflare…" toasts on normal pages (e.g. GitHub): detection used to match the word "verify" in the page title, which is often stale from the previous page; now URL-based only

#### v8.2

- History/bookmark list decodes percent-encoded URLs for display (no more `%E5%AE%B9` gibberish), truncates overlong URLs; display-only, stored data untouched

#### v8.1

- Address bar selects all only on first tap; later taps place the cursor normally (the "always select all" was too aggressive — reverted)

#### v8 (Edge-style dual toolbar)

- Top address row = [engine chip][wide input][refresh/stop combo]; bottom nav row = [back][forward][home][new tab +][tab count][⋯ menu]
- Refresh/stop combo: ✕ while loading (stops it), refresh icon when done
- New home button; address-bar position setting kept (top default / bottom); About page now reads versionName

#### v7

- Home page: removed the middle card, pure wallpaper

#### v6

- ⋯ menu: removed Translate (unreachable from CN networks), restored Settings
- Engine quick-switch chip in the address bar (Bing / Yandex / Google / DuckDuckGo, Bing first, Bing default on fresh installs)

#### v5

- Direct-edit address bar (EditText, tap-to-select-all + keyboard, enter to go)
- Wallpaper home (fully local/offline): wallpaper library, gallery pick, long-press web image to set, rotation, management
- Edge-style Settings (Appearance & layout / Search engine / Wallpaper / Privacy & security / Accessibility / Default browser / About)

#### v4

- All 24 icons replaced with Google Material Icons (Round, white); UI polish (corners/spacing/ripples)
- Address-bar position setting (top/bottom); switchable default search engine (Google / Bing / DuckDuckGo / Yandex)

#### v3

- Edge-style UI: bottom toolbar + slide-up menu; multi-tab (max 10); bookmarks; system DownloadManager; desktop-UA toggle; find in page; TTS read-aloud; Settings page

#### v2

- Auto-tap keyword expansion (embedded Turnstile); browsing history; incognito; domain-level ad blocking (StevenBlack, ~74k rules); video sniffing + built-in player; custom ghost icon
