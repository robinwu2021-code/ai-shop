# 品牌资产 · 虹选 / HX Mall

归属：**深圳虹选科技有限公司**

> **换代已完成（2026-08-20）**：红 `#e1251b` + 扁弧母题 + 自绘 H/X 同源字形，
> C 端与 B 端一并切过去，旧的虹橙 G4 几何已从 `build.py` 删除。
> 参数见 [spec.html](./spec.html) §02，逐项对照见 [icon-proposal.html](./icon-proposal.html)。

## 这个目录是单一真源

所有端的图标与品牌色都由 `build.py` 从同一组几何参数派生。

```bash
python3 brand/build.py
```

**不要手改任何单个产物文件。** 改 `build.py` 顶部的参数，重跑，全端一致。
手改会在下次重跑时被覆盖，而且会造成「某一端的图标和别处不一样」——
这正是这套管线要消灭的问题。

依赖 headless Chrome 渲位图（macOS 默认路径已内置，可用 `CHROME=` 覆盖）。

## 色

| Token | 值 | 用途 | 对比度 |
|---|---|---|---|
| `--hx-red` | `#E1251B` | 主色 · 标识、主按钮、C/B 端图标底 | 压白字 **4.69** ✓ AA |
| `--hx-red-deep` | `#B31710` | 浅底上的红字、hover | 白底 **6.89** · 面板 **6.37** ✓ |
| `--hx-red-bright` | `#FF5A4D` | 深色模式主色、母品牌弧线 | 压墨 **5.77** · 压深板岩 **4.65** ✓ |
| `--hx-ink` | `#17181A` | 墨 · 正文、单色稿、母品牌底 | 白底 **17.77** |
| `--hx-plate-merchant` | `#242B33` | 深板岩（B 端底色的备选，当前未启用） | 亮红弧 4.65 |

**三条不可互换**：灰底红字用 `#B31710`（主色压面板只有 4.33）；深色模式主色用 `#FF5A4D`（主色压深底 3.72）；`#FF5A4D` 上的字用墨不用白（白字 3.08）。

引用方式：`tokens.css`（CSS 变量）、`tokens.ts`（TS 常量）、`tokens.json`（其它工具链）。

## 形

**四档，各有各的值**（`build.py` 的 `TIERS`；规范 §02 本来就是分档的）：

| 档 | 字高 | 弧线宽 | 用在哪 |
|---|---|---|---|
| `H` | 0.64 × 边长 | **无弧线** | 头像、favicon、通知栏（16px 要能辨） |
| `HX` | 0.30 × 边长 | 0.44 × 边长 | App 图标、站点主标 |
| `CN1` 虹 | 0.46 × 边长 | 0.40 × 边长 | 小程序中文版、包装 |
| `CN2` 虹选 | 0.38 × 边长 | 0.38 × 边长 | 双字方章，弧仍只压「虹」 |

两个母题级参数：

- **`arc_flat` 0.65** —— 弧从正半圆压成扁圆（竖半径 ÷ 横半径）。横向占位不变，纵向省 31%。再扁到 0.50，弧内净空不足两个描边宽，小尺寸下读成一条直线。
- **`bar` 0.38** —— H 横画中心高度 ÷ 字高。这不是新造型：**hxmall 原始素材的 H 路径量出来就是 0.380**，早先 `_h_at()` 把它写死在正中 0.50 才是走样。单独 H 与 HX 里的 H 同值，两处不同就是两套字形。

## 两端

| | 底板 | 字 | 弧 |
|---|---|---|---|
| C 端 · 虹选好物 | 主色红 | 白 | 白 |
| B 端 · 虹选商家 | 主色红 | 白 | 白 |

⚠️ **两端图标目前几乎一样，只靠桌面名区分**（虹选好物 / 虹选商家）。
规范 §4.5 原本给 B 端定的是深板岩 `#242B33` + 亮红弧（压深板岩 4.65 ✓），
桌面上一眼分得开 —— 商家手机上两个 App 都装是常态。08-19 拍板改成红底，
理由是「深板岩在一堆彩色图标里读作系统工具」。**这一项仍待定**：
改回深板岩只需把 `APPS["b"]` 的 `plate` 换成 `PLATE_B`、`arc` 换成 `RED_BRIGHT`，重跑。

## 产物去了哪

| 端 | 路径 |
|---|---|
| Android C 端 | `android-shell/app/src/consumer/res/` |
| Android B 端 | `android-shell/app/src/merchant/res/` |
| iOS | `brand/ios/AppIcon-{c,b}.appiconset/` |
| C 端 H5 | `c-app/public/` |
| B 端 H5 | `b-app/public/` |
| 运营端 | `ops-web/public/` |
| **官网** | `site/public/`（含 `favicon.ico` / `site.webmanifest` / `og.png` / `share-300.png`） |
| 应用商店 · 小程序 | `brand/store/` |
| **启动页 · 通知图标** | `android-shell/app/src/main/res/{values,values-v31,drawable}/` |
| **商标申报稿** | `brand/trademark/`（黑白 + 反白 × H / HX / 虹选） |

Android 走 **v26+ 自适应（矢量前景，永不失真）+ 旧版位图兜底**双轨。
`AndroidManifest.xml` 已挂 `android:icon` / `android:roundIcon` / `android:theme`。

**启动页不引 `core-splashscreen`**：这个仓库没有那个依赖，继承 `Theme.SplashScreen`
会直接编译不过。改用平台属性 `android:windowSplashScreenBackground` / `...AnimatedIcon`
（API 31+ 自带，compileSdk 34 可解析），31 以下退回 `drawable/splash.xml` 当窗口背景。
底色 `@color/splash_background` 按 flavor 各给各的。

## 上架前还要注意

- **iOS 1024 不得带 alpha 通道** —— 带了 App Store 直接拒。当前产物已是 RGB 无 alpha，改管线时别破坏这一点。
- **图标源不烘焙圆角** —— iOS 与 Android 各自套遮罩，预切会二次圆角，边缘被削一圈。
- 桌面显示名与图标是两件事。当前 `app_name` 仍是「社区好物 / 邻里商家」，改名见品牌方案 §4.3。
