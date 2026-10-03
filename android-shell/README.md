# android-shell —— 开发预览用的 WebView 壳，**不是上架的那个包**

一套壳代码、两个 flavor：

| flavor | applicationId | 装出来叫 |
|---|---|---|
| `consumer` | `ai.neargo.shop.c` | 社区好物 |
| `merchant` | `top.hxmall.bapp` | 邻里商家 |

⚠️ **`shell_entry` 是按 buildType 配的，不按 flavor** —— release 两个 flavor
都加载 `http://106.55.27.246/b/`（B 端），debug 都加载 `http://localhost:5174`
（b-app 的 dev server）。也就是说**现在打出来的 consumer 包，装上看到的是商家端**。
要用壳看 C 端，得先把 `build.gradle` 里的 `shell_entry` 改成按 flavor 给
（`productFlavors` 块里各写一条 `resValue`，会覆盖 buildType 那条）。

## ⚠️ 先看这条：`merchant` 与真·B 端 APK **同包名**

真机上发给商家的「邻里商家」不是从这里打出来的，而是走 **DCloud uni 离线打包**
（`HBuilder-Integrate-AS/simpleDemo`，来自 DCloud Android 离线 SDK —— 只在网盘上发布，
不在本仓库里；DCloud appid `__UNI__59E912D`）。

而 `merchant` flavor 的 `applicationId` 正是 `top.hxmall.bapp`，**和真包一模一样**。
后果是：

- 谁在测试机上装了这里打的 merchant 包，**真包就被顶掉了**（反之亦然）；
- 交付时拿错包不会有任何报错 —— 界面几乎一样，只是原生能力少一半，
  等到验高德定位、扫码、支付时才发现，而那时已经过了几轮。

**要给别人装的 B 端 APK，一律从离线打包出**。这里的 merchant flavor 只用于自己看界面。

## 两者的区别

壳只有 WebView + 少量原生桥：**推送已接**（个推原生 SDK + `PushBridge`），
微信/支付宝支付、扫码、高德定位都没有。离线打包出来的是真 uni 原生 app，
跑 HTML5+ 运行时，上面那些都在。

所以：看界面、走流程、验推送 —— 壳够用；联调支付、定位、扫码 —— 必须离线包。

**别把新的原生 SDK 手写进这个壳**。写进来的集成代码不会跟着上架，
到时候要在离线包里重做一遍。

## 改了 b-app，通常不用重新打包

release 的 `shell_entry` 指向线上 `/b/`，壳每次启动去加载那份 H5 ——
**b-app 改动只要发 H5 就行**，不必重打 APK、不必重装。
只有动原生能力（推送/权限/高德/tabBar）才需要重打。

发 H5 时两条别忘：

- `H5_BASE=/b/ npm run build:h5` —— 不带这个变量，产物引用 `/assets/` 绝对路径，线上 404；
- 线上 HTML 要有 `Cache-Control: no-cache`，否则 WebView 把 `index.html` 缓存住，
  **发了新版真机上还是旧界面**。nginx 配置快照在 `deploy/tencent/nginx/`。

## 打离线包时：**必须抬 versionCode**

改 `b-app/src/manifest.json` 的 `versionCode`（与 `versionName`）。同版本号
`adb install -r` 覆盖之后，uni 运行时会继续用上一版解出来的 `www` ——
而且不是整份旧：**页面代码走新、页面注册表走旧**。表现是「老页面的新内容都对，
新增页面 `navigateTo` 静默不跳，logcat 一个字都没有」。排查这种症状先 `adb uninstall`
重装，再怀疑代码。

## 相关文档

- `docs/technical/design/App签名与打包参数.md` —— 证书、SHA1、各平台 Key、离线包注入
- `MainActivity` 类注释 —— 壳自身的边界
