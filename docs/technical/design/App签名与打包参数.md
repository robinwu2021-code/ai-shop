# App 签名与打包参数

状态：生效中（2026-08-14）
关联：[ADR-018 App 生产形态与推送通道](../adr/ADR-018-App生产形态与推送通道.md)、[品牌工程与官网方案](品牌工程与官网方案.md) §1.2

这份文档存**填第三方后台时要抄的那几个值**：包名、签名指纹、密钥放在哪。

它存在的理由很实际：这些值每接一个 SDK 就要填一次（个推、微信开放平台、支付宝、
厂商推送通道、App Links），而算指纹要有密钥在手 —— 没有这份文档，
下一个人只能来问「keystore 在谁那儿」，或者更糟，**自己再生成一把**，
于是同一个包名有了两个签名，线上包和他打的包互相装不上。

## 1. 应用标识

| 端 | 安卓包名 / iOS BundleId / 鸿蒙 bundleName |
| --- | --- |
| B 端（商家） | `top.hxmall.bapp` |
| C 端（消费者） | **未拍板** —— 见 §4 |

**三端用同一个标识**是有意的：iOS 与鸿蒙都允许与安卓同名，而个推、微信开放平台
这类后台的结构是「一个应用、多个平台」，同名最省事。三端都还没上架，没有历史包袱。

落点在 `b-app/src/manifest.json` 的 `app-plus.distribute`（`android.packagename` /
`ios.bundleId`）。**上架之后不可改** —— 改包名等于全新 App：老用户必须重装、
本地数据全丢、商店评分清零。

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

**哪个后台要哪一个**：个推要 SHA256；微信开放平台要 MD5；
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

## 4. 未决：C 端包名与前缀之争

B 端定了 `top.hxmall.bapp`（对应域名 `hxmall.top`），而
《品牌工程与官网方案》§1.2 写的是「包名 `ai.neargo.shop.*` 不改」，
理由是改包名等于全新 App。

**那条理由的前提是「已经发过版」，而三端目前都没上架** —— 前提不成立，
现在改是零成本，上架之后再改才是那个后果。

要拍的三件事：

1. C 端包名定成什么（按同一条线是 `top.hxmall.capp`）；
2. 是否整体收敛到 `top.hxmall.*`（**两套前缀并存的代价**：证书、深链
   Universal Links / App Links 会各绑各的域名 —— `hxmall.top` 与 `neargo.ai`
   要分别做站点验证，配错的表现是链接打不开 App 而是打开浏览器）；
3. §1.2 那条决策改写成「上架前统一迁到 `top.hxmall.*`，上架后冻结」。

在此之前 `c-app/src/manifest.json` 里的包名留空，并在那里留了指向本节的注释。

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

**不要把个推 SDK 手写进 `android-shell/`。** 那个壳自述是开发预览用的 WebView 壳
（见 `MainActivity` 类注释），没有原生能力，注定不是上架的那个包 ——
写进去的集成代码会连壳一起丢掉。
