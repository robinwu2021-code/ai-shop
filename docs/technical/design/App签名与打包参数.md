# App 签名与打包参数

状态：生效中（2026-08-14）
关联：[ADR-018 App 生产形态与推送通道](../adr/ADR-018-App生产形态与推送通道.md)、[品牌工程与官网方案](品牌工程与官网方案.md) §1.2

这份文档存**填第三方后台时要抄的那几个值**：包名、签名指纹、密钥放在哪。

它存在的理由很实际：这些值每接一个 SDK 就要填一次（个推、微信开放平台、支付宝、
厂商推送通道、App Links），而算指纹要有密钥在手 —— 没有这份文档，
下一个人只能来问「keystore 在谁那儿」，或者更糟，**自己再生成一把**，
于是同一个包名有了两个签名，线上包和他打的包互相装不上。

## 1. 应用标识

**「包名」是两件事，这里只管其中一件**（2026-08-14 拍板）：

| | 值 | 谁在用 |
| --- | --- | --- |
| **发布标识**（安卓包名 / iOS BundleId / 鸿蒙 bundleName） | B 端 `top.hxmall.bapp`；C 端未定 | 应用商店、个推、微信开放平台等第三方后台 |
| **代码命名空间**（Java 包、模块名、目录） | `ai.neargo.shop.*`，**不动** | 编译器与人 |

两者本来就该解耦，混为一谈才是常见的错。改代码命名空间要动上千个文件的
`package` 与 `import`，而收益是零 —— **用户永远看不见 Java 包名**。
这也让《品牌工程与官网方案》§1.2「包名不改」那条继续成立：它说的是代码那一件。

发布标识落在 `b-app/src/manifest.json` 的 `app-plus.distribute`
（`android.packagename` / `ios.bundleId`），**上架之后不可改** ——
改它等于全新 App：老用户必须重装、本地数据全丢、商店评分清零。

**三端（安卓/iOS/鸿蒙）用同一个发布标识**是有意的：iOS 与鸿蒙都允许与安卓同名，
而个推、微信开放平台这类后台的结构是「一个应用、多个平台」，同名最省事。

> `android-shell/app/build.gradle` 里那个 `applicationId "ai.neargo.shop.b"`
> **不用改**：那是开发预览用的 WebView 壳，不是要上架的包（见 ADR-018）。

## 2. 正式签名

| | |
| --- | --- |
| keystore | `~/keys/hxmall-release.jks`（**仓库外**，权限 600） |
| 别名 | `hxmall` |
| 证书主体 | `CN=Shenzhen HongXuan Technology, O=Shenzhen HongXuan Technology Co. Ltd, L=Shenzhen, ST=Guangdong, C=CN` |
| 有效期 | 10950 天（约 30 年，到 2056 年） |
| 生成于 | 2026-08-14 |

指纹（冒号分隔十六进制）：

```
MD5:    70:66:2F:16:07:78:E6:10:D3:53:2F:5E:CE:EA:8B:56
SHA1:   54:05:63:0C:E3:38:3F:00:CA:CF:16:8F:09:D2:66:7E:BA:EE:94:4D
SHA256: 75:B8:7C:C9:F6:D0:9A:76:C3:3C:0C:B0:24:DA:BD:4A:40:80:D6:B5:1B:5A:55:4F:65:18:9D:30:D7:2E:6C:D4
```

去冒号小写（部分后台的输入框不收冒号）：

```
md5    70662f160778e610d3532f5eceea8b56
sha1   5405630ce3383f00cacf168f09d2667ebaee944d
sha256 75b87cc9f6d09a76c33c0cb024dabd4a4080d6b51b5a554f65189d30d72e6cd4
```

**哪个后台要哪一个**：个推要 SHA256；微信开放平台要 MD5；高德开放平台（Android Key）要 SHA1；
App Links 的 `assetlinks.json` 要 SHA256；华为/荣耀推送要 SHA256。

### 密钥与密码

- 密钥：`~/keys/hxmall-release.jks`。**这是整条链路上唯一不可逆的东西** ——
  丢了，`top.hxmall.bapp` 这个包名就再也发不出可信更新，只能换包名重新上架。
  必须有至少两处离线备份。
- 密码：`android-shell/signing/keystore.properties`（明文、600、`.gitignore` 已挡）。
  **同时记进密码管理器** —— 这台机器换了，密码就跟着没了，而密码没了等于密钥没了。

### 重新生成指纹

```bash
cd android-shell && ./gen-release-keystore.sh
```

已存在就跳过生成、只打指纹。脚本从证书 DER 字节算三个摘要，
与 `keytool -list -v` 的口径一致（已逐位核对过）。

## 3. debug 签名

调试包用 `~/.android/debug.keystore`（Android SDK 自带，`CN=Android Debug`）：

```
MD5:    89:EF:09:88:8D:A3:A3:49:35:98:92:9C:D5:D0:6B:43
SHA1:   B7:8A:F1:AA:C0:62:94:BF:8F:19:7E:D9:8B:0C:AF:31:B8:69:3F:E2
SHA256: 2D:33:A1:46:12:BE:AF:22:E9:F7:DD:45:06:C9:8A:C0:3D:CA:41:18:78:5A:A9:2E:E3:46:25:59:0E:89:E7:A6
```

**它与正式签名不通用。** 拿 debug 指纹去填生产后台，表现是「一条推送都收不到」
或「微信授权失败」，而报错文案通常只说「应用未注册」—— 看不出是签名对不上。
每台开发机的 debug.keystore 都不一样，上面这组只对这一台有效。

## 4. 未决：C 端的发布标识

「前缀之争」已经不存在了 —— §1 拍板：**代码命名空间与发布标识分开**，
前者留在 `ai.neargo.shop.*`，后者用 `top.hxmall.*`。两者不冲突，
《品牌工程与官网方案》§1.2 说的是代码那一件，继续成立。

剩下的只有一条：**C 端的发布标识定成什么**。按同一条线是 `top.hxmall.capp`
（或 `top.hxmall.app`，如果把 C 端当主应用）。定之前
`c-app/src/manifest.json` 里留空，并在那里留了指向本节的注释。

> 深链（App Links / Universal Links）的站点验证跟着**发布标识**走：
> `assetlinks.json` 要放在 `hxmall.top` 下、写 `top.hxmall.bapp` 与本文 §2 的
> SHA256。与代码命名空间无关。

## 5. 推送的集成参数

按 ADR-018，推送走 uni-push 2.0，端上**不写原生代码**：
`b-app/src/manifest.json` 里 `sdkConfigs.push.unipush` 已配好，
`uni.getPushClientId()` 拿到的就是个推 cid。

还缺的行政件（与开发并行推进）：

- DCloud 账号 + `manifest.json` 的 `appid`（两个端现在都是空的，云打包必需）；
- uni-push 控制台的 appId / appKey / masterSecret → 后端 `GETUI_APP_ID` / `GETUI_APP_KEY`；
- 厂商通道资质（小米/华为/OPPO/vivo/荣耀逐家申请），拿到后把
  `sdkConfigs.push.unipush.offline` 改 `true`，后端零改动；
- iOS 的 APNs 证书，依赖 Apple 开发者账号。

**不要把新的原生 SDK 手写进 `android-shell/`。** 那个壳是开发预览用的 WebView 壳
（见 `android-shell/README.md`），注定不是上架的那个包 ——
写进去的集成代码不会跟着上架，到时候要在离线包里重做一遍。

（个推是例外：壳里**已经**接了原生个推 + `PushBridge` JS 桥，为的是在没有离线包的
那段时间能验推送。上架那条路仍然走 uni-push 2.0，端上不写原生代码。）

## 6. 高德地图 Key（2026-08-22 接入）

高德开放平台 → 应用管理 → 添加 Key，**按平台各一个**：Android 填包名 `top.hxmall.bapp` + §2 的 SHA1
（可把 debug SHA1 用 `;` 一并填上），iOS 填 BundleID `top.hxmall.bapp`，H5/小程序另申请 Web 端 Key。

Key 不进仓库：写在 `b-app/.env.local`（根 `.gitignore` 已挡）。
**模板见 `b-app/.env.local.example`** —— 那份进仓库，是这几个变量唯一可校验的载体。

| 变量 | 平台 | 谁读它 |
| --- | --- | --- |
| `AMAP_KEY_ANDROID` | Android SDK | `b-app/offline/amap-key.gradle` → `manifestPlaceholders` → AndroidManifest 的 `com.amap.api.v2.apikey` |
| `AMAP_KEY_IOS` | iOS SDK | 还没有（iOS 离线打包链路未建） |
| ~~b-app 的 Web 端 JS API~~ | — | **不申请**（2026-08-28 拍板：店主用 App，B 端 H5 只我们自己调试用；后果见 `utils/geo.ts`） |
| `AMAP_WEB_KEY` | Web 服务 | 后端 `application.yml` 的 `amap-key`（在 `backend/.env.local`） |
| `NEXT_PUBLIC_AMAP_JS_KEY` + `_SECURITY_CODE` | Web 端 JS API | `ops-web/lib/amap.ts`（在 `ops-web/.env.local`） |

**前两个没有 `VITE_` 前缀是有意的**：Vite 只把 `VITE_*` 注入浏览器 bundle，而 SDK key
的消费者是 Gradle 构建时。挂上 `VITE_` 等于给「哪天有人 `import.meta.env` 一下就把
SDK key 打进 JS 产物」留门。

> ⚠️ **2026-08-22 到 08-28，这条注入实际上是断的。**
> 当时那段代码直接写在离线工程的 `build.gradle` 里，而那个工程在仓库外
>（DCloud 离线 SDK 目录、网盘发布、不受版本控制）—— 重解压一次就被覆盖没了。
> 复核过两个包（机上装着的 149、当天新打的 155）：manifest 里都没有
> `com.amap.api.v2.apikey`，整个 APK 的 strings 里也搜不到那把 key。
> 而**三边都看不出来**：文档写着「已注入」、构建成功、装机不报错，
> 只有真机点定位报错误码 7，文案还看不出是没配 key。
>
> 现在的做法：注入逻辑放在**仓库里**（`b-app/offline/amap-key.gradle`），
> 离线工程只留一行 `apply from:` —— 一行的缺失一眼看得出，几十行的逻辑丢了看不出。
> key 读不到时**构建直接失败**，不打一个静默坏掉的包。
> 另有两道守卫：`b-app/offline/verify-apk.sh` 验产物里有没有这个 meta，
> `packages/shared/tests/env-consumed.test.ts` 验「模板里声明的变量代码里有没有人读」。

`b-app/src/manifest.json` 的 `sdkConfigs.geolocation.amap` / `maps.amap` 只负责选提供方，`appkey_*` 留空。

Key 不对的表现：定位 fail 且原生错误码 **7（KEY 鉴权失败）**；地图白屏。
模拟器上另有两条与 key 无关的假阴性：SIM 为美国运营商（MCC 310）时高德 SDK 走海外链路报错误码 4「网络连接异常」，
关掉蜂窝后变错误码 2「WIFI信息不足」（模拟器没有真实 AP/基站，且高德默认丢弃 mock GPS）——地图瓦片能正常渲染即说明 key 已通过，定位要真机验。

