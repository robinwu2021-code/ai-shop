# App 离线打包 · 操作手册

状态：生效中（2026-08-28 按当天真机发版的实际步骤整理）
关联：[App签名与打包参数](./App签名与打包参数.md)、[ADR-018 App 生产形态与推送通道](../adr/ADR-018-App生产形态与推送通道.md)

这份文档存**打一个能装上真机的 B 端 APK 要按顺序做的那几件事**。

它存在的理由：这条链路**一半在仓库里、一半在仓库外**（DCloud 离线 SDK 工程在
`~/Downloads` 下，网盘分发、不受版本控制），而仓库里此前只有签名参数与
产物体检脚本 —— 中间「怎么从代码走到 APK」没有任何地方写。
2026-08-28 这次发版是靠翻工程现状反推出来的：产物路径、www 换装位置、
两处版本号、体检脚本的用法，每一条都得现找。下一个人不该再找一遍。

> ⚠️ **不要用 `android-shell/`。** 那是开发预览用的 WebView 壳，`applicationId`
> 与正式包**同名**，装上去会顶掉真包，而且不报错。见 ADR-018。

---

## 先看这个：整条链路已经有一条命令

```bash
b-app/offline/build-apk.sh                      # 打包 + 强制体检
b-app/offline/build-apk.sh --install <真机序列号>  # 再装机自检
```

它把下面 §1–§6 全串起来了，并且**先认工程**（§0）：路径在、应用名是「虹选商家」、
两行 `apply from:` 接线在、keystore 与 JDK 在 —— 缺一样就停，**不自作主张去解压**。

立这一道的原因：2026-08-30 有人（我）凭「工程被清掉就重新解压」那句话，
把 SDK 压缩包解压到 /tmp 打了一份，结果是**厂商默认工程** —— 应用名
`HBuilder-SimpleDemo-AS`、无高德 key、四个原生库一个都没有，产物 30M（真包 55M），
而 gradle 一路 BUILD SUCCESSFUL。关键在于：§0 之外的那两道构建期闸门
（`amap-key.gradle` / `version-alignment.gradle`）**是靠离线工程里那两行
`apply from:` 接进来的**，用错工程时它们不是失败，是**根本没跑**。

已配好的工程一直在 `~/Downloads/最新版/5.24/**sdk/**` 下 —— `sdk` 是目录，
与同名 `.zip` 并列，`ls` 到 zip 就以为工程没了。

脚本还把**版本号收成单一真源**：只改 `b-app/src/manifest.json`（在仓库里、可 review），
离线工程 gradle 那处由脚本同步。§1 的「两处一起抬」不再需要人记着。

下面各节是这条命令每一步在做什么，出问题时照着排查。

---

## 0. 前置（一次性，已经配好就跳过）

| 东西 | 在哪 | 备注 |
| --- | --- | --- |
| 离线 SDK 工程 | `~/Downloads/最新版/5.24/**sdk/**Android-SDK@5.24.82669_20260813/HBuilder-Integrate-AS` | **别重解压**：`sdk` 是目录，与同名 .zip 并列，ls 到 zip 容易误以为工程没了。工程**真的**不见了才解压那个 zip —— 解出来是厂商默认工程，应用名/图标/高德 key/各 aar 全要重配 |
| 离线 AppKey | `simpleDemo` 的 AndroidManifest（`dcloud_appkey`） | 丢了能从旧包取回，命令见 §5 的 `AAPT` 那一行 |
| 签名 keystore | `~/keys/hxmall-release.jks` | 密码在 `android-shell/signing/keystore.properties`（未跟踪） |
| Android 命令行工具 | `/opt/homebrew/share/android-commandlinetools` | `local.properties` 指向它；`verify-apk.sh` 也读这个路径 |
| 高德 Android Key | `b-app/.env.local` 的 `AMAP_KEY_ANDROID` | 注入逻辑在 `b-app/offline/amap-key.gradle`，工程里只有一行 `apply from:` |

**这两行 `apply from:` 都必须在**（`simpleDemo/build.gradle` 末尾）：

```
apply from: "/Users/robin/work/ai/ai-shop/b-app/offline/amap-key.gradle"
apply from: "/Users/robin/work/ai/ai-shop/b-app/offline/version-alignment.gradle"
```

第二行是 **versionCode 两处对齐**的构建时闸门（见下一节）。它挂在 `preBuild` 上，
`assemble` / `bundle` 哪条路都绕不过；对不上就让构建失败，并把两个数字和该改哪两处打出来。

把注入逻辑放仓库里、工程里只留一行，是因为**一行的缺失一眼看得出，几十行的逻辑丢了看不出** ——
2026-08-22 到 08-28 这条注入断了六天没人发现，就是因为它当时整段写在工程里，
而工程重解压一次就被覆盖没了。

---

## 1. 抬版本号 —— **两处，缺一处就打出静默坏包**

> 2026-08-28 起这一步有闸门兜底了（`b-app/offline/version-alignment.gradle`）：
> 两处对不上就构建失败。**但闸门只在 `apply from:` 那行还在时才存在** ——
> 离线工程重解压会把它清掉，所以上一节那两行要一起检查。
>
> 立这道闸的直接原因：仓库里本来就有 `verify-apk.sh`，里面本来就有「版本号」那一节，
> 但它只打印不比对，而且**没有任何自动流程调用它**。当天一个 APK=159 / www=158 的包
> 就这么发了出去，在测试真机上跑了两个多小时。**只能靠人记得跑的守卫等于没有守卫。**

| 改哪 | 决定什么 |
| --- | --- |
| `simpleDemo/build.gradle` 的 `versionCode` / `versionName` | **APK manifest** 里的版本。系统用它判断能不能覆盖安装 |
| `b-app/src/manifest.json` 的 `versionCode` / `versionName` | 流进 `www/manifest.json`。**DCloud 运行时用它决定要不要重新解压 www** |

只抬 gradle 那一处，装是装上了、`aapt2` 看到的版本是新的，
但**运行时会沿用手机上已解压的旧 www** —— 新代码静默不生效，
而版本号显示的是新的。这个症状看起来像「代码没编进去」，方向完全不对。

两处要一致。产物自检见 §5。

---

## 2. 构建 App 资源

```bash
cd b-app && npm run build:app
```

产物在 `b-app/dist/build/app/`。**不需要 HBuilderX**。

⚠️ **确认 API base 指对了地方。** `uni build` 走 production 模式，读
`b-app/.env.production`（当前是 `VITE_API_BASE=http://106.55.27.246`）。
如果本机有人为了跑模拟器把它改成了 `http://10.0.2.2:8085`，
**打出来的包在真机上一个接口都不通** —— 2026-08-28 的 157 号包就是这样，
而它在模拟器上一切正常，看不出问题。

打完可以直接在产物里核一眼：

```bash
grep -o 'const [A-Za-z_$]*="http://[^"]*"' b-app/dist/build/app/app-service.js | head
#   const Be="http://106.55.27.246"     ← 真机要的是这个
#   const Be="http://10.0.2.2:8085"     ← 这个只在模拟器里通，真机全是「网络异常」
```

---

## 3. 换 www

```bash
OFF=~/Downloads/最新版/5.24/sdk/Android-SDK@5.24.82669_20260813/HBuilder-Integrate-AS
W="$OFF/simpleDemo/src/main/assets/apps/__UNI__59E912D/www"

rm -rf "$W.prev" && cp -R "$W" "$W.prev"     # 留一份可回退的
rm -rf "$W"      && cp -R b-app/dist/build/app "$W"
```

`__UNI__59E912D` 是 `b-app/src/manifest.json` 里的 `appid`，两边必须一致。

留 `.prev` 不只是为了回退：**它还是「我的改动到底进没进包」的判据**。
产物是压缩过的，变量名被改掉，按源码里的名字去 grep 一定搜不到。
可行的做法是与上一版对比：

```bash
cmp -s "$W/app-service.js" "$W.prev/app-service.js" && echo "逐字节相同 —— 改动没进去"
```

---

## 4. 打包

```bash
cd "$OFF" && ./gradlew :simpleDemo:assembleRelease
```

产物：`simpleDemo/build/outputs/apk/release/simpleDemo-release.apk`

**打 release 不打 debug** —— debug 包会弹一串 HTML5+ Runtime 调试提示。

---

## 5. 产物体检 —— **构建成功不等于包是好的**

```bash
b-app/offline/verify-apk.sh <新包.apk> <上一个已知能用的包.apk>
```

这条链路上出过的每一次事故，**构建都是成功的**：

- 重解压 DCloud SDK → 高德 key 的注入被覆盖没了 → 界面全对，只有定位报错误码 7；
- 重解压还会静默清掉应用名与图标 → 装上叫「HBuilder」，图标是默认小机器人；
- assets 换了但 dex 没换 → `unzip -l` 看不出来（库在不在要数 dex 里的类）。

三种都得装到手机上、点到那个功能才会发现，所以体检要在装机之前跑。
脚本会核：高德 key 在不在且不是占位符、应用名是不是「虹选商家」、
`.so` 清单与图标资源和旧包一致、四个大库（igexin / 微信 OpenSDK / 高德 / dcloud）
的类计数与旧包同数。

**顺带核一下两处版本号对齐**（§1 那两处）。
`aapt2` **不在 PATH 上**，要按版本号目录展开 —— 这一行照抄，别写成 `aapt2 ...`：

```bash
AAPT=$(ls /opt/homebrew/share/android-commandlinetools/build-tools/*/aapt2 | tail -1)

"$AAPT" dump badging <新包.apk> | grep '^package:'
#   package: name='top.hxmall.bapp' versionCode='158' versionName='0.4.31' ...

python3 -c "import json;print(json.load(open('$W/manifest.json'))['version'])"
#   {'name': '0.4.31', 'code': '158'}
```

两行的 code 必须是同一个数。同一个 `$AAPT` 也用来取 AppKey：

```bash
"$AAPT" dump xmltree <旧包.apk> --file AndroidManifest.xml | grep -A2 dcloud_appkey
```

---

## 6. 装机与验证

```bash
adb devices -l                                   # 认准真机的序列号，别装到模拟器上
adb -s <序列号> install -r <新包.apk>
```

装完至少核这四样，**都不需要解锁手机**：

```bash
adb -s <序列号> shell dumpsys package top.hxmall.bapp | grep -E "versionName|versionCode"
adb -s <序列号> shell monkey -p top.hxmall.bapp -c android.intent.category.LAUNCHER 1
adb -s <序列号> shell pidof top.hxmall.bapp                        # 进程活着＝没启动即崩
adb -s <序列号> logcat -d -t 200 | grep -iE "FATAL|AndroidRuntime"
```

**从手机这一侧探一次后端** —— 这一步验的是「包里那个 base 在这台机器的网络下真能用」，
在本机 curl 验不了（本机 DNS 走内网代理，与手机走的路不是一条）：

```bash
adb -s <序列号> shell 'curl -s --max-time 12 http://106.55.27.246/actuator/health'
# 期望 {"groups":[...],"status":"UP"}；回 HTML 或超时 = 端口被劫持或网络不通
```

界面要点的话得解锁手机，那一步交给人。

---

## 7. 收尾

- `b-app/src/manifest.json` 的版本号**提交进仓库**（gradle 工程在仓库外，没法提交）。
- 旧包留一份：下次体检要拿它当对照，没有对照的体检只剩三条绝对检查。

---

## 常见坑速查

| 症状 | 多半是 |
| --- | --- |
| 装上了，新页面打不开，版本号却是新的 | §1 只抬了 gradle 那一处，运行时沿用旧 www |
| 真机上每个列表都「网络异常」，模拟器正常 | §2 的 `.env.production` 被改成了 `10.0.2.2` |
| 定位报错误码 7、地图白屏 | 高德 key 没进包 —— §5 的第一项会拦住 |
| 装上叫「HBuilder」、图标是小机器人 | 重解压 SDK 覆盖了应用名与图标 —— §5 会拦住 |
| 装不上，提示签名冲突 | 机上装的是 `android-shell` 那个预览壳（同包名），先卸载 |
| 弹「不匹配的版本可能造成应用异常」 | App 资源的编译器版本与离线 SDK 不一致，要对齐 `@dcloudio/*` 版本重编 |
| 包只有 30M（真包 55M）、装上叫 HBuilder | 用了重解压出来的厂商默认工程 —— `build-apk.sh` 的 §0 会拦住 |
| 定位报错误码 7，而体检脚本说 key 在 | 体检的是产物，构建期两道闸门却没跑（工程里少了 `apply from:` 两行）|
