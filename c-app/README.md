# ai-shop C 端 App（uni-app）

社区电商 C 端。**微信小程序优先（P0）**、Android/iOS 次之（P1），一套 uni-app（Vue3+TS+Vite）代码，条件编译隔离端差异。

> 方案：[../docs/technical/TDD-c-app.md](../docs/technical/TDD-c-app.md) · 需求：[../docs/requirements/C端功能清单.md](../docs/requirements/C端功能清单.md)

## 技术栈

uni-app（Vue3+TS+Vite）· wot-design-uni · UnoCSS(presetApplet) · Pinia(+persist) · vue-i18n（中/英/阿 + RTL）· Capacitor 8

**三处版本硬约束，勿随手升级**（理由见 TDD §3.1）：

| 包 | 锁定 | 原因 |
|----|------|------|
| `vite` | **精确 5.2.8** | `@dcloudio/vite-plugin-uni` 的 peer 精确钉死此版本 |
| `typescript` | `~5.9.3` | TS 7 与 vue-tsc / uni-app 工具链未验证 |
| `@dcloudio/*` | `3.0.0-5010520260709002` | `latest` 标签指向 **Vue2 线**，装错会整个跑不起来 |
| `@dcloudio/types` | 精确 `3.4.31` | uni-app peer 精确要求，不能取 latest |
| `unocss` / `unocss-applet` | 成对锁 | applet peer 是 `~66.7.5` |

## 运行

```bash
npm install
npm run dev:h5          # H5 预览（http://localhost:5173）
npm run dev:mp-weixin   # 微信小程序，产物导入微信开发者工具
npm run build:h5
npm run build:mp-weixin
npm run type-check      # vue-tsc
```

## mock ↔ 真实后端一键切换（核心地基）

页面统一 `import { api } from "@/api"` 调 `api.xxx()`，**不感知 mock/真实**。切换只改 `.env`，页面零改：

**mock 落盘**：购物车、订单、卡包写进 `uni.storage`，刷新后还在。
mock 要**像真后端一样** —— 只放内存的话一刷新就没了，那不是「后端行为」，
会掩盖真接后端时才暴露的问题（例如「刷新后购物车空了」在真环境根本不会发生）。
库存不落盘：它是种子数据的一部分，每次启动重新播种，比持久化一份会漂移的副本更可预期。

```bash
VITE_USE_MOCK=1                        # 默认：走内存 mock，UI 脱离后端跑通
# VITE_USE_MOCK=0
# VITE_API_BASE=http://localhost:8080  # 切真实后端 /mp/**
```

## 目录

```
src/
├── api/          唯一契约 ShopApi + mock/http/一键切换（index.ts）
├── types/        契约镜像（OrderStatus 等，与后端同源）
├── mock/db.ts    全域 mock 数据集 + 订单状态机（非法迁移抛错）
├── stores/       Pinia：user / community(社区归属) / cart / theme
├── ports/        端能力抽象 + 条件编译：auth/payment/share/theme/scan/location/media/push
├── strategies/   ★ 品类扩展点：pricing(计价) + fulfillment(履约)
├── design/       主题 token（CSS 变量 + 原生栏色值）
├── shared/       constants(零硬编码) + format(金额/重量/时间) + goods(取值守卫)
├── i18n/         zh-CN / en / ar 三语文案
├── shared/money.ts     多货币格式化（最小单位整数 → 展示串）
├── shared/datetime.ts  时区感知的时间格式化
├── shared/fly.ts       加入购物车动效状态机
├── components/   ui/sh-*（easycom）· biz/biz-*（easycom）
└── pages/        home / category / goods(详情) / cart / me / community / login
```

## 品类扩展点

五个品类共用一条交易主干，差异只下沉到「计价 + 履约」两个策略位：

| 品类 | PricingStrategy | FulfillmentStrategy |
|------|-----------------|---------------------|
| `GOODS` 日用品 | `fixed` 固定价 + 满额包邮 | `pickup` / `express` |
| `FRESH` 生鲜 | `weighed` 约重多退少补 | `pickup` 预售 + 取货码 |
| `SERVICE` 服务 | `noFreight` 按次 | `storeVerify` 到店 / `appointment` 预约 |
| `VIRTUAL` 虚拟商品 | `noFreight` | `instant` 支付即发码 |
| `CARD` 卡券 | `noFreight` | `instant` 支付即入卡包 |

新增品类只要注册两个策略实现，**交易主干、订单状态机、售后、佣金一行不用改**。
`instant` 履约还带一个 `instant: true` 标记 —— 支付成功后直接走到完成态，不经备货与核销。

## 多规格（SKU 矩阵）

`Goods.specGroups` 定义维度（如 容量 × 香型），`Sku.optionValues` 是各维度上的取值，下标一一对应。
详情页按维度逐行渲染，**并对每个取值做「固定其它维度、是否存在有货 SKU」的探测**，
不可组合的取值直接置灰 —— 没有这层判断，用户能选出一个根本不存在的组合。

选中态用**实底主色**而不是 tint：`mono` 这类低饱和皮肤下 tint 与 faint 几乎没差别，选没选中看不出来。

## 图标

`sh-icon` + `design/icons.ts`，内联 SVG 转 data-URI 作 **CSS mask**，颜色由 `background-color` 提供 ——
于是图标能直接吃 `var(--sh-*)`，换肤零成本。

不用 emoji：各系统字形不一致（同一个 🛒 在 iOS / Android / 微信里长得都不一样），而且是彩色的，
没法跟着皮肤主色走。不用 `<image>`：颜色要跟随 4 套皮肤 × 明暗，位图得准备一堆变体。

底部菜单用**线性/实心两态**（未选线性、选中实心），比只靠颜色区分更清晰。

> mask 的跨端表现只在 H5 实测过。小程序真机需要复验 —— 若某些低版本 WebView 不支持，
> 图标会变成纯色方块，届时回退方案是给 `sh-icon` 加一层 `background-image` 兜底。

## 加入购物车动效

小球从点击处飞向购物车图标，落地时图标弹一下（`shared/fly` + `sh-fly-cart`）。
用 fixed 定位 + CSS transition，不用 `uni.createAnimation`（跨端行为差异大），也不用 Web Animations API（小程序不支持）。

**落点不写死**：tab 页的落点在底部菜单的购物车上，详情页的落点在操作条的购物车入口上，
两处都在自己挂载后把实际位置上报（`registerCartAnchor`），详情页离开时撤销。

三个已踩过的坑：

1. **事件必须透传**。`biz-goods-card` 的 `add` 事件要 `$emit('add', $event)`。不透传的话页面里
   `$event` 是 `undefined`，`tapPoint` 抛错被 catch 吞成一个 toast —— **加购正常、动效静默失效**，
   日志里什么都看不到。`tapPoint` 现在对空事件回落到屏幕中心而非抛错。
2. **坐标系要对齐**。`createSelectorQuery` 返回**页面坐标**，飞行层是 `position: fixed` 的**视口坐标**。
   H5 端 uni 把导航栏画在 DOM 里，两者差一个 `windowTop`（44px）。不补这段，小球会稳定落在
   购物车正上方 44px 处 —— 看着「差一点点」，很难归因。小程序导航栏是原生的，`windowTop` 为 0。
3. **量元素要等它存在**。详情页操作条挂在 `v-if="goods"` 下，`onMounted` 时商品还没加载，
   量不到就会悄悄退回兜底落点。改成数据到位后再量。

## 字体

拉丁走 **Inter**、阿拉伯走 **Noto Kufi Arabic**（均为可变字体，一个 woff2 覆盖全字重），
中日韩落到系统 `PingFang SC` / `Noto Sans SC`。字体栈的排序就是「按字符找字体」的优先级。

**中文不配远程字体**：子集动辄数 MB，移动端不可接受，系统中文字体质量也足够。
拉丁与阿拉伯的系统默认（尤其小程序 WebView 里的）字形较弱，正是要补的地方。

H5 用 `<link>` 引 Google Fonts（浏览器按 `unicode-range` 只下需要的子集），
App / 小程序走 `ports/font.ts` 的 `uni.loadFontFace`。加载失败静默回落到系统字体栈。

⚠️ **生产必须自托管**：`fonts.gstatic.com` 在中国大陆访问不稳定；微信小程序还要求 `loadFontFace`
的域名进 downloadFile 白名单。把 woff2 放自己的 CDN，只需改 `ports/font.ts` 里两个常量。

## 设计语言：扁平色块

分层靠**面色 + 色块底（tint）**，不靠描边与阴影 —— 全局几乎没有 1px 线条。
圆角偏大、留白偏松，信息密度低于国内电商惯例。强调色只用于「可点」与「状态」，不做装饰。

公共积木定义在 `App.vue`（全局非 scoped）：`sh-card` / `sh-chip`（+`--primary/--warning/--danger`）/
`sh-btn`（+`--soft/--muted`）/ `sh-h1` `sh-h2` `sh-muted` / `sh-num`。

具体做法：
- 列表项之间用 **间距 + 独立圆角块** 分隔，不用 `border-bottom`
- 标签、规格、频道入口一律是 **tint 色块**，不描边
- 价格不用大红堆砌，靠 **字重 + 字号 + 留白** 分层，折扣单独做成一个色块

## 货币与时区

**市场（地区）决定货币 + 时区，语言独立切换** —— 中东用户也可能用英文，中国用户也可能看英文界面。
市场配置在 `shared/constants` 的 `MARKETS`，状态在 `stores/market`。

- **金额**一律「最小货币单位」整数流转（人民币分 / 美分 / 菲尔），只在展示层格式化。
  `CURRENCIES` 里的 `minorUnits` 决定小数位 —— 接日元这类零小数货币只需改配置。
  符号位置也可配（AED 符号在数字后）。
- **不用 `Intl.NumberFormat`**：小程序基础库对 Intl 支持不稳定，`shared/money` 手写格式化保证跨端一致。
- **时间戳一律 UTC 毫秒**流转，`shared/datetime` 按市场时区渲染。生鲜截单是「市场本地 21:00」，
  不做换算的话海外用户看到的截单时刻是错的。
- **订单货币在创建时锁定**（`OrderAmount.currency`），不随用户之后切市场变化。

⚠️ 两个已知限制，都写进了 TDD 待办：
1. 时区用**固定 UTC 偏移**而非 IANA 时区 —— 小程序没有可靠 tz 数据库，而目标市场（中国 +8 / 海湾 +4）无夏令时。进入有夏令时的市场必须换方案。
2. mock 用**固定汇率**换算价格。真实系统里商品应按市场分别定价，不是汇率换算。

## 三语与 RTL

中文 / English / العربية，阿语走 **RTL 整体镜像**（`direction: rtl` 挂在 `.sh-root`，全端通用）。
文案在 `i18n/locale/`，语言状态在 `stores/app`，方向写入在 `ports/direction`。

### 底部菜单已改为自定义组件

原生 tabBar 有三个躲不开的问题：字号锁死（改不大）、不吃 CSS 变量（换肤要 `setTabBarStyle`）、
不吃 i18n（切语言要 `setTabBarItem` 逐个改写）。换成 `sh-tabbar` 之后这三件事由 CSS 变量与 `$t`
自然解决，运行时改写全删了。

⚠️ **`pages.json` 的 `tabBar.custom` 只有微信小程序认**。H5 / App 仍会渲染原生 tabBar 并盖住自定义的
（表现为底部出现两套菜单、语言还不一致）。`tabBar` 配置本身不能删 —— `switchTab` 依赖它 ——
所以 `App.vue` 里用条件编译把原生的隐藏掉，并清掉它给页面留的底部占位。

导航栏标题仍是原生的，靠 `sh-scaffold` 的 `title-key` prop + `uni.setNavigationBarTitle` 运行时改写
（首屏 `onLaunch` 时导航栏可能尚未就绪，所以每页挂载时补一次）。

### 切语言必须重拉服务端文案

社区/自提点/购物车里的文案是**绑定当时那门语言的快照**（真实后端按 `Accept-Language` 返回）。
切语言时 `sh-theme-sheet` 会调 `community.refreshLocalized()` + `cart.load()` 重拉，
否则页面上会出现中英阿混排。mock 侧同样在「出口」处本地化（`toGoods` / `toCommunity` / `cartList`）。

### RTL 下数值要单独隔离

金额、百分比、倒计时是 LTR 序列，跟着 RTL 走会把符号甩到另一端（`-25%` 会显示成 `25%-`）。
`sh-num` 里带了 `direction: ltr; unicode-bidi: isolate;` —— **所有数值文本都要挂 `sh-num`**。

## 主题（UI 风格切换）

4 套皮肤（`fresh` 生鲜绿 / `promo` 促销橙 / `mono` 极简黑 / `blue` 商务蓝）× 明暗（浅/深/跟随系统），切换即时全局生效。

### 两个已踩过的坑（改主题前必读）

**1. 同一个变量在同一条继承链上只许声明一次。**
自定义属性一旦声明在元素自身，就永远胜过从祖先继承的值。最初把默认值同时写在 `page` 和
`.sh-root` 上，导致 `:root[data-theme="dark"]` 的覆盖被挡掉 —— **换肤静默失效，肉眼完全看不出来**。
现在严格分层：主题无关常量在 `:root, .sh-root`；主色只在 `skin-*` 规则里；中性面只在 `mode-*` 规则里。

**2. CSS 注释里不能出现 `*/` 字符组合。**
注释里写 `skin-*/mode-*` 会提前闭合注释，PostCSS 直接报 `Unexpected '/'`，整个 App.vue 样式编译失败。

### 原生栏不吃 CSS 变量

tabBar 与导航栏由客户端渲染，只能在换肤时用 `uni.setTabBarStyle` / `uni.setNavigationBarColor`
运行时改写 —— 所以同一份色板在 `design/tokens.ts` 里以 `SKIN_HEX` / `MODE_HEX` **再暴露一次**，
改 CSS 变量时必须同步改这里。

## 端差异

登录 / 支付 / 分享 / 主题 / 扫码 / 定位 / 媒体 / 推送 8 项在 `ports/*` 隔离。
**页面永远不写 `#ifdef`** —— 所有条件编译只出现在 `ports/` 内部。

## 里程碑

- **M0 工程地基** ✅ 脚手架 + 请求层一键切换 + stores/ports/strategies/design + tabBar + 选社区 + 登录 + 换肤 + **三语/RTL** + **商品详情（三态）**（H5 与小程序均构建通过，H5 实测三语 × 四皮肤 × 明暗）
- M1 交易闭环（全 mock）：详情/结算/收银台/订单/自提码/售后
- M2 活动 + 裂变：券·满减·拼团·分享卡片·海报·邀请归因
- M3 团长：招募/申请/经营台（分拣单·核销台·群发助手·佣金）
- M4 接真后端 + 微信支付真付 + Capacitor 出包上架
