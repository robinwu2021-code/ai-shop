#!/usr/bin/env python3
"""IPA 产物体检 —— 对应 Android 那边的 `verify-apk.sh`。

用法：
    unzip -q <ipa> -d <tmp> && python3 b-app/offline/ios/verify-ipa.py <tmp>/Payload/*.app

## 为什么判据全写成「等于我们的值」

离线工程解压出来是 DCloud 的**演示应用 HelloH5**，它的 Info.plist 里
`dcloud_appkey` / `amap.appkey` / `getui.*` **全都有值** —— 演示账号的值。
安卓拿错工程一眼看得出（应用名不对、高德 key 为空），iOS 这边每个字段都"填了"，
**任何「非空即通过」的检查都会放行**，打出来的包安静地用演示账号连高德和个推。

## 为什么要有它（而不是只靠 altool --validate-app）

2026-09-28 首次打包，`--validate-app` **两次都通过**，而 App Store Connect 的
处理阶段两次都失败，页面上只有「失败」两个字、不给原因。validate 检查的是
"这个包能不能收"，与"这个包是不是我们要的那个"是两件事。

下面这几条就是那两次失败逼出来的：
  · `CFBundleIconName`（**顶层**，不是 CFBundleIcons 里那个）—— 用 asset catalog
    的 App 苹果强制要，Xcode 一般自动补，而这个工程是手工维护的 plist，没人补。
  · 图标要去 `Assets.car` 里数，**不是数 .app 根目录的松散 PNG** ——
    现代 iOS 把图标编进 Assets.car，根目录只留两三个旧系统兼容用的，
    数那几个会得出"只有 2 个图标"的错误结论（我自己先栽了一次）。
"""
import plistlib, sys, subprocess, json, re, os, glob
app = sys.argv[1]
d = plistlib.load(open(f"{app}/Info.plist", "rb"))
R = "/Users/robin/work/ai/ai-shop"
env = {}
for line in open(f"{R}/b-app/.env.local", encoding="utf-8"):
    if "=" in line and not line.lstrip().startswith("#"):
        k, _, v = line.strip().partition("="); env[k] = v.strip()
raw = open(f"{R}/b-app/src/manifest.json", encoding="utf-8").read()
mf = json.loads(re.sub(r"/\*.*?\*/", "", re.sub(r"^\s*//.*$", "", raw, flags=re.M), flags=re.S))
cat = json.loads(subprocess.run(["xcrun","assetutil","--info",f"{app}/Assets.car"],
                                capture_output=True, text=True).stdout or "[]")
rend = {e.get("RenditionName") for e in cat if e.get("Name") == "AppIcon"}
sig = subprocess.run(["codesign","-dvvv",app], capture_output=True, text=True).stderr
prof = plistlib.loads(subprocess.run(["security","cms","-D","-i",f"{app}/embedded.mobileprovision"],
                                     capture_output=True).stdout)
www = json.load(open(f"{app}/Pandora/apps/__UNI__59E912D/www/manifest.json", encoding="utf-8"))
C = []
def eq(l, got, want): C.append((got == want, l, got, want))
eq("CFBundleIdentifier", d.get("CFBundleIdentifier"), "top.hxmall.bapp")
eq("显示名", d.get("CFBundleDisplayName"), "虹选商家")
eq("版本 Short 等于 manifest", d.get("CFBundleShortVersionString"), mf["versionName"])
eq("版本 Build 等于 manifest", d.get("CFBundleVersion"), str(mf["versionCode"]))
eq("www 版本与包一致", str(www["version"]["code"]), d.get("CFBundleVersion"))
eq("顶层 CFBundleIconName", d.get("CFBundleIconName"), "AppIcon")
eq("市场图标 1024", "icon-1024.png" in rend, True)
eq("iPad Pro 167", "icon-167.png" in rend, True)
eq("iPhone @3x 180", "icon-180.png" in rend, True)
eq("图标总数 ≥ 12", len(rend) >= 12, True)
eq("dcloud_appkey 等于我们的", d.get("dcloud_appkey") == env["DCLOUD_APPKEY_IOS"], True)
eq("amap.appkey 等于我们的", (d.get("amap") or {}).get("appkey") == env["AMAP_KEY_IOS"], True)
eq("无个推演示值", "getui" in d, False)
eq("出口合规已声明", d.get("ITSAppUsesNonExemptEncryption"), False)
eq("定位文案非空", bool((d.get("NSLocationWhenInUseUsageDescription") or "").strip()), True)
eq("启动页 storyboard", d.get("UILaunchStoryboardName"), "LaunchScreen")
eq("隐私清单在包里", os.path.exists(f"{app}/PrivacyInfo.xcprivacy"), True)
eq("签名主体", "Apple Distribution: NearGo L.L.C-FZ (72TUZXTHY5)" in sig, True)
eq("aps 环境", prof["Entitlements"].get("aps-environment"), "production")
eq("非开发签名", prof["Entitlements"].get("get-task-allow"), False)
hits = subprocess.run(["grep","-rl","10.0.2.2","-e","127.0.0.1","-e","localhost:8081",
                       f"{app}/Pandora"], capture_output=True, text=True).stdout.strip()
eq("www 无本机地址", hits, "")
ok = all(c[0] for c in C)
for g,l,got,want in C:
    print(f"  {'✓' if g else '✗'} {l:26s} {got}" + ("" if g else f"  期望 {want}"))
print("\n" + (f"全部 {len(C)} 项通过" if ok else "✗ 有不通过项，别传"))
sys.exit(0 if ok else 1)
