# App 离线打包 · iOS 操作手册

> 与《App离线打包-操作手册》（Android）同构。**这条链路一半在仓库里、一半在仓库外**，
> 中间那段没人写就会丢 —— 那篇立档的理由，这篇照样成立。
>
> 状态：2026-09-30 第二次打通到 TestFlight（0.5.21 / 254，VALID）。首次是 2026-09-28（0.5.0 / 229）。

## 1. 仓库外的东西在哪

| 东西 | 路径 | 谁给的 |
| --- | --- | --- |
| iOS 离线 SDK | `~/Downloads/latest/5.26/sdk/SDK/` | DCloud（百度网盘/和彩云，**只能人工下**） |
| Xcode 壳工程 | 同上 `HBuilder-Hello/` | SDK 自带 |
| 苹果密钥与证书 | `~/work/env/apple/`（700，文件 600） | 见《App签名与打包参数》§7 |
| 第三方 key | `b-app/.env.local`（gitignore） | 模板在 `b-app/.env.local.example` |

**SDK 版本**：当前用 5.26，而 `b-app` 的编译器是 5.24（`@dcloudio/*` 的
`3.0.0-5020420260813003`）。DCloud 的运行时向下兼容，**SDK 比编译器新是允许的**，
反过来才危险。代价只是三端运行时版本不一致。

## 2. 一次性工程配置（重解压 SDK 后要重做一遍）

### 2.1 Podfile：按实际用到的能力开关 subspec

打开：`Barcode`（扫码）、`Geolocation-Gaode` + `Map-Gaode`（定位与地图）、
`Push-Getui`（推送，**待 `GETUI_APPSECRET` 到位**）。
关掉：`Geolocation-Tencent`（与高德同时在会互相打架）、以及 b-app 一处都没调用的
`Contacts` / `IBeacon` / `Fingerprint` / `Proximity` / `Audio`
—— 关掉它们等于少声明通讯录、蓝牙、Face ID、麦克风四个权限，**每个多余权限都是一次审核问答**。

判据是查调用点，不是猜：

```bash
for api in chooseContact startBeaconDiscovery startSoterAuthentication createInnerAudioContext; do
  echo "$api $(grep -rl "$api" b-app/src packages/shared/src | wc -l)"
done
```

⚠️ **两行 `source` 要注释掉**。工程里所有 pod 都是 `:path` 本地路径，
留着那两行会让 `pod install` 去 clone 几个 G 的 Specs 仓库（国内尤其慢）。

⚠️ **`pod install` 必须带 UTF-8 locale**，否则它连 Podfile 都读不到：

```bash
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install --no-repo-update
```

报错长这样，看不出是 locale 的事：
`Unicode Normalization not appropriate for ASCII-8BIT (Encoding::CompatibilityError)`。

### 2.2 身份：**必须逐项覆盖，判据写「等于我们的值」**

解压出来的是 DCloud 的**演示应用 HelloH5**，而它的 `Info.plist` 里
`dcloud_appkey`、`amap.appkey`、`getui.*` **全都有值** —— 演示账号的值。

安卓拿错工程一眼看得出（应用名 `HBuilder-SimpleDemo-AS`、高德 key 为空）；
iOS 这边每个字段都「填了」，**任何「非空即通过」的检查都会放行**，
打出来的包会安静地用 DCloud 演示账号去连高德和个推。

要覆盖的：

| 键 | 值 | 不覆盖的后果 |
| --- | --- | --- |
| `CFBundleDisplayName` / `CFBundleName` | 虹选商家 | 桌面上显示 HelloH5 |
| `dcloud_appkey` | `DCLOUD_APPKEY_IOS` | 用演示账号跑 |
| `amap.appkey` | 由 `gen-uniapp-config.py` 注入 | 同上 |
| `getui` 整段 | 删掉，待生成器重写 | 同上 |
| `ITSAppUsesNonExemptEncryption` | `false` | **每次传包都要人工回答一次加密合规问题** |

### 2.3 权限文案：官方模板里定位那条是**空串**

`NSLocationWhenInUseUsageDescription` 解压出来是 `""`。
空串在 iOS 上会让定位请求**被静默拒绝** —— 界面上什么都不会发生，也不报错。
上架时这条还会直接被拒。

每条都要说清「为什么要」，官方那份是「照相机」「通讯录」这种，等于没写。

### 2.4 签名：只改 app target，别用命令行传

```
error: Pods-HBuilder does not support provisioning profiles, but provisioning
profile HXMall Merchant AppStore has been manually specified.
```

`xcodebuild ... PROVISIONING_PROFILE_SPECIFIER=...` 会**作用到所有 target**，
Pods 那个不支持描述文件，直接构建失败。要写进 app target（`HBuilder`）
自己的 Release 配置块。

⚠️ **模板里留着带 `[sdk=iphoneos*]` 后缀的同名键**，它们会**盖过**无后缀的那条：

```
"CODE_SIGN_IDENTITY[sdk=iphoneos*]" = "iPhone Developer";
"PROVISIONING_PROFILE_SPECIFIER[sdk=iphoneos*]" = "dev-wildcard";
```

只改无后缀的那条，构建时用的仍是**开发证书**，而报错会指向签名本身、不指向这里。
要连后缀项一起删。

### 2.5 图标：那个 `icon/` 文件夹**根本没被工程引用**

工程里有 `HBuilder-Hello/icon/icon*.png` 共 17 个尺寸，
但它们**不在 Copy Bundle Resources 里**，`CFBundleIcons` 是空字典 ——
照着填那 17 个文件，包里依然**零图标**，传上去会被拒。

做法：建 asset catalog 并挂进工程。源图用 `brand/ios/AppIcon-b.appiconset/icon-1024.png`
（**1024 不得带透明通道**，否则 App Store 拒）。改工程文件可以借 CocoaPods 自带的
`xcodeproj` gem：

```bash
L=/opt/homebrew/Cellar/cocoapods/*/libexec; GEMS=$(ls -d $L/gems/*/lib | tr '\n' ':')
RUBYOPT="-E UTF-8" ruby -e "\$LOAD_PATH.unshift(*'$GEMS'.split(':').reject(&:empty?)); load 'add_assets.rb'" <工程>.xcodeproj
```

⚠️ 那段 ruby **不能带中文注释**：系统 ruby 默认 US-ASCII，会在**解析阶段**就失败
（`invalid multibyte char`），一行都不会跑 —— 而它前面的步骤看起来都成功了。

### 2.6 部署目标：Xcode 升级会把「警告」变成「直接失败」

2026-09-30 本机 Xcode 升到 27，`archive` 直接失败（不是警告）：

```
error: The iOS deployment target 'IPHONEOS_DEPLOYMENT_TARGET' is set to 13.0,
but the range of supported deployment target versions is 15.0 to 27.0.x.
```

§5 里那句「2027-04 才要抬到 15」说的是 **App Store 上传侧**；而 **Xcode 本地构建侧**
被工具链版本逼得更早 —— Xcode 27 最低只收 15.0。**三处都要抬，漏一处那个 target 照样让 archive 失败**：

```bash
# ① Podfile
sed -i '' "s/platform :ios, '13.0'/platform :ios, '15.0'/" Podfile
# ② app 工程：所有 <15 的（这个模板里有 13.0 也有 6.0）
sed -i '' 's/IPHONEOS_DEPLOYMENT_TARGET = 13.0;/IPHONEOS_DEPLOYMENT_TARGET = 15.0;/g; \
           s/IPHONEOS_DEPLOYMENT_TARGET = 6.0;/IPHONEOS_DEPLOYMENT_TARGET = 15.0;/g' \
  HBuilder-Hello.xcodeproj/project.pbxproj
# ③ 重跑 pod install（Podfile 改了必须重装，它会把大部分 Pods target 设成 15）
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install --no-repo-update
# ④ pod install 后仍可能残留个别 pod 自带 13.0（不受 Podfile platform 覆盖）——直接抬掉
sed -i '' 's/IPHONEOS_DEPLOYMENT_TARGET = 13.0;/IPHONEOS_DEPLOYMENT_TARGET = 15.0;/g' \
  Pods/Pods.xcodeproj/project.pbxproj
```

判据：`grep -c 'IPHONEOS_DEPLOYMENT_TARGET = 1[0-4]\.' <三个 pbxproj>` 全为 0。

### 2.7 ExportOptions.plist：工程里没有，要自己建（重解压会丢）

`xcodebuild -exportArchive` 需要它，而**解压出来的工程根目录没有这个文件**（DCloud 不带）。
缺了报 `Couldn't load -exportOptionsPlist ... no such file`。内容（值从描述文件本身读，别猜）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>72TUZXTHY5</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key>
  <dict><key>top.hxmall.bapp</key><string>HXMall Merchant AppStore</string></dict>
  <key>uploadSymbols</key><true/>
  <key>destination</key><string>export</string>
</dict></plist>
```

描述文件的真实字段：`security cms -D -i ~/work/env/apple/HXMall_Merchant_AppStore.mobileprovision`
里的 `Name`（profile 名）、`TeamIdentifier`、`application-identifier`（去掉 team 前缀是 bundle）。
`method` 用 `app-store-connect`（Xcode 15+；旧的 `app-store` 仍兼容）。

### 2.8 体检脚本的参数是 .app，不是 .ipa

`verify-ipa.py` 收的是**解压后的 `.app`**，直接喂 .ipa 会报 `NotADirectoryError`：

```bash
unzip -q <ipa> -d /tmp/unz
python3 b-app/offline/ios/verify-ipa.py /tmp/unz/Payload/*.app
```

## 3. 每次打包（可重复的部分）

```bash
# ① 第三方 key → 离线工程（缺值直接失败，不是警告）
python3 b-app/offline/ios/gen-uniapp-config.py <工程目录>

# ② www
cd b-app && npm run build:app
rm -rf <工程>/HBuilder-Hello/Pandora/apps/__UNI__59E912D/www
cp -R b-app/dist/build/app <工程>/HBuilder-Hello/Pandora/apps/__UNI__59E912D/www

# ③ control.xml 的 appid 要与目录名一致，appver 与 manifest 一致；debug=false
# ④ 版本号：MARKETING_VERSION / CURRENT_PROJECT_VERSION ← b-app/src/manifest.json
# ⑤ 归档与导出
xcodebuild archive -workspace HBuilder-Hello.xcworkspace -scheme HBuilder \
  -configuration Release -destination "generic/platform=iOS" \
  -archivePath /tmp/x.xcarchive \
  OTHER_CODE_SIGN_FLAGS="--keychain $HOME/Library/Keychains/hxmall-ios.keychain-db"
xcodebuild -exportArchive -archivePath /tmp/x.xcarchive \
  -exportOptionsPlist ExportOptions.plist -exportPath /tmp/ipa
```

**版本号是双源的**，与安卓同构：`CFBundleVersion`（消费者=iOS 系统）
与包内 `www/manifest.json` 的 `version.code`（消费者=DCloud 运行时）。
只抬前者 → 装得上、系统显示新版，**而运行时沿用旧 www**，新代码静默不生效。
体检里有这一条断言。

## 4. 产物体检（传之前必须过）

判据**一律写成「等于我们的值」**，不写「非空」—— 理由见 §2.2。当前 16 项：

Bundle ID · 显示名 · 版本 Short/Build · `dcloud_appkey` 等于我们的 ·
`amap.appkey` 等于我们的 · 出口合规已声明 · 定位文案非空 · 无个推演示值 ·
www 版本与包一致 · `CFBundleIcons` 指向 AppIcon · 包里有 AppIcon 图片 ·
签名主体是 `Apple Distribution: NearGo L.L.C-FZ (72TUZXTHY5)` ·
TeamIdentifier · 描述文件 `aps-environment=production` · `get-task-allow=false`

## 5. 上传

```bash
cp ~/work/env/apple/AuthKey_PVSW227SPD.p8 ~/.appstoreconnect/private_keys/
xcrun altool --validate-app -f <ipa> -t ios --apiKey PVSW227SPD \
  --apiIssuer 4aa221ae-ce21-4e62-b6a3-fc8d7c7961c7
xcrun altool --upload-app  -f <ipa> -t ios --apiKey ... --apiIssuer ...
```

**先 validate 再 upload**：两者的检查项一样，而 validate 不占用构建号。

已知的非阻塞警告：`90068 Deployment target too low`（iOS 13）。
2027 年 4 月起 App Store 要求 15+，届时要抬 `platform :ios` 与 target 的部署目标。

## 6. 这一版还缺什么

- **推送**：`Push-Getui` 暂时关着，等 `GETUI_APPSECRET`（个推后台要短信验证码才能看）。
  个推那边 iOS 包名已经绑好 `top.hxmall.bapp`，还差上传 APNs 密钥
  （`AuthKey_HM4F5HVQTN.p8`，Key ID `HM4F5HVQTN`，Team ID `72TUZXTHY5`）。
- **微信**：要先在 `hxmall.top` 挂 Universal Links，开放平台审核另算时间。
- **Apple 登录**：后端 `GRANT_APPLE` 只有 TODO，不校验 identityToken 就信任请求里的
  principal。**接上去等于把账号送人**。

后两者互相绑定：苹果只在「App 提供了第三方登录」时才强制要求 Apple 登录，
两个都不带就不触发这条规则。
