# 第三方组件许可声明

本应用内置的 VPN 加密隧道引擎基于以下 MIT 许可证的开源项目，
以 gomobile JNI 方式编译进 APK、在应用进程内运行：

- usque（github.com/Diniboy1123/usque）——Go 实现的 Cloudflare WARP / MASQUE 隧道核心；
- usque-android（github.com/exxojay/usque-android）——其 mobile/ 包的 gomobile 绑定接口
  设计被本应用用于进程内集成（注册 / 建隧道 / SOCKS5）。

按 MIT 许可证要求，保留版权声明与许可文本如下：

---

The MIT License (MIT)
=====================

Copyright © 2025, github.com/Diniboy1123
Copyright (c) 2026, 8DE4732A

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
