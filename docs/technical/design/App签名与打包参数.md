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
- ~~iOS 的 APNs 证书，依赖 Apple 开发者账号~~ —— **2026-09-28 已办**，见 §7。

**不要把新的原生 SDK 手写进 `android-shell/`。** 那个壳是开发预览用的 WebView 壳
（见 `android-shell/README.md`），注定不是上架的那个包 ——
写进去的集成代码不会跟着上架，到时候要在离线包里重做一遍。

（个推是例外：壳里**已经**接了原生个推 + `PushBridge` JS 桥，为的是在没有离线包的
那段时间能验推送。）

> ⚠️ **这里原先写着「上架那条路仍然走 uni-push 2.0，端上不写原生代码」，已被实现推翻。**
> 实际走的是**个推原生直连**：`b-app/src/manifest.json` 刻意**不声明** uni-push 模块
> （uni-push 2.0 要 DCloud 实名认证，没开通时 register 报 errorCode 1），
> 端上由 `packages/shared/src/ports/push.ts` 取 cid。照旧文走会再踩一次那个错误码。

## 6. 高德地图 Key（2026-08-22 接入）

高德开放平台 → 应用管理 → 添加 Key，**按平台各一个**：Android 填包名 `top.hxmall.bapp` + §2 的 SHA1
（可把 debug SHA1 用 `;` 一并填上），iOS 填 BundleID `top.hxmall.bapp`，H5/小程序另申请 Web 端 Key。

Key 不进仓库：写在 `b-app/.env.local`（根 `.gitignore` 已挡）。
**模板见 `b-app/.env.local.example`** —— 那份进仓库，是这几个变量唯一可校验的载体。

| 变量 | 平台 | 谁读它 |
| --- | --- | --- |
| `AMAP_KEY_ANDROID` | Android SDK | `b-app/offline/amap-key.gradle` → `manifestPlaceholders` → AndroidManifest 的 `com.amap.api.v2.apikey` |
| `AMAP_KEY_IOS` | iOS SDK | **2026-09-28 已申请**（高德「虹选」应用下的 `hxmall-bapp-ios`，绑 BundleID）。消费者待建：iOS 侧对应 `amap-key.gradle` 的那个注入脚本还没有，值先放着 |
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
> **2026-08-28 真机验证时又发现第二条缺失**：`com.amap.api.location.APSService`
> 这个 service 在 149 与 158 两个包里**都没有**（一直缺着）。它与 key 的症状分得很开 ——
> 少 key 是地图白屏 + 错误码 7；少 service 则**地图照常渲染**，只有 `AMapLocationClient`
> 起不来，logcat 里一行 `Unable to start service … APSService: not found`。
> 也就是说「地图能看」不足以说明定位这条链是通的。
>
> 现在的做法：注入逻辑放在**仓库里**（`b-app/offline/amap-key.gradle`），
> 离线工程只留一行 `apply from:` —— 一行的缺失一眼看得出，几十行的逻辑丢了看不出。
> key 读不到时**构建直接失败**，不打一个静默坏掉的包；
> AndroidManifest 里那两条声明（meta-data + service）同样在仓库外，
> 所以那个脚本也把它们校一遍，缺了就失败并把该粘回去的原文打出来。
> 另有两道守卫：`b-app/offline/verify-apk.sh` 验产物里有没有这个 meta，
> `packages/shared/tests/env-consumed.test.ts` 验「模板里声明的变量代码里有没有人读」。

`b-app/src/manifest.json` 的 `sdkConfigs.geolocation.amap` / `maps.amap` 只负责选提供方，`appkey_*` 留空。

Key 不对的表现：定位 fail 且原生错误码 **7（KEY 鉴权失败）**；地图白屏。
模拟器上另有两条与 key 无关的假阴性：SIM 为美国运营商（MCC 310）时高德 SDK 走海外链路报错误码 4「网络连接异常」，
关掉蜂窝后变错误码 2「WIFI信息不足」（模拟器没有真实 AP/基站，且高德默认丢弃 mock GPS）——地图瓦片能正常渲染即说明 key 已通过，定位要真机验。


## 7. 苹果侧资源（2026-09-28 办齐）

打 iOS 包要的东西**全部在仓库外**：密钥文件在 `~/work/env/apple/`（700，文件 600），
标识记在同目录的 `apple.env`。**这一节只记标识，不记任何密钥内容** —— 与高德 Key 同一条规矩，
但更严：`.p8` 一旦泄露，别人能以我们的名义发推送、传包。

| 项 | 值 | 谁用它 |
| --- | --- | --- |
| Team ID | `72TUZXTHY5`（NearGo L.L.C-FZ，组织账号） | 签名、APNs 的 `iss`、描述文件 |
| Bundle ID | `top.hxmall.bapp`（与安卓包名同，见 §1） | 全部 |
| App Store Connect App ID | `6816814379`（「虹选商家」，主语言简体中文，SKU `hxmall-bapp`） | 传包、TestFlight |
| 发布证书 | `7L852DQR7M`，Apple Distribution，2027-09-28 到期 | `codesign` |
| 描述文件 | `HXMall Merchant AppStore`（IOS_APP_STORE） | 打包 |
| APNs 密钥 | Key ID `HM4F5HVQTN`，Sandbox & Production，Team Scoped | 后端直连 APNs / 个推 iOS |
| 传包密钥 | ASC Key ID `PVSW227SPD`，Issuer `4aa221ae-ce21-4e62-b6a3-fc8d7c7961c7`，权限「App 管理」 | `xcrun altool` 传 TestFlight |

**App ID 上开了三个能力**：Push Notifications、Sign in with Apple、Associated Domains。
后两个是为微信 Universal Links 与 Apple 登录预留的 —— **首版都不接**（理由见下）。

### 签名不弹窗的做法

证书私钥**不进 login 钥匙串**：那个要交互式输开机密码才能给 `codesign` 授权，脚本里过不去。
改成专用钥匙串 `hxmall-ios.keychain-db`，密码由脚本生成并记在 `apple.env`：

```bash
security create-keychain -p "$PW" hxmall-ios.keychain-db
security import ios_distribution.p12 -k hxmall-ios.keychain-db -P "$P12PW" -T /usr/bin/codesign -A
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PW" hxmall-ios.keychain-db
security list-keychains -d user -s login.keychain-db hxmall-ios.keychain-db
```

⚠️ `openssl pkcs12 -export` **别加 `-legacy`**：本机 `/usr/bin/openssl` 是 LibreSSL，不认这个参数，
而它的默认格式正好是 macOS 钥匙串能收的。（加了会静默不产出文件 —— 报错在 stderr，
被 `2>/dev/null` 吞掉后看起来像成功。）

### 首版 TestFlight 的范围

**不接微信、不接 Apple 登录**，理由各不相同：

- **微信**：iOS 应用要先在 `hxmall.top` 挂 Universal Links，开放平台审核另算时间。
- **Apple 登录**：后端 `AuthServiceImpl` 的 `GRANT_APPLE` 只有一个 TODO，
  **不校验 identityToken 就信任请求里的 principal**（见那里的注释）。接上去等于把账号送人。

两者是**互相绑定的**：苹果只在「App 提供了第三方登录」时才强制要求 Apple 登录。
首版两个都不带，就不触发这条审核规则；等后端验签补好、微信审下来，同一版一起加。
