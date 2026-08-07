# TDD-c-app（C 端一期技术方案）

状态：**已确认（2026-08-05）· M0 已实现**
关联需求：[../requirements/C端功能清单.md](../requirements/C端功能清单.md) §五 一期范围（已确认）· §五之二 UI 风格切换
关联架构：[architecture.md](./architecture.md)
参考工程：`powerbank/c-app`（同一套地基，已在 MENA 项目验证）
创建日期：2026-08-05

---

## 1. 需求摘要

跑通**单运营方自营**的社区电商最小闭环：三品类（生鲜/日用品/服务）日常购买 + 团购，基础营销活动（券/满减/限时/新人礼），裂变（分享卡片/海报/邀请有礼/归因），团长开团与经营台，UI 风格可切换。

**一期验收**：
用户 登录 → 选社区自提点 → 浏览三品类 → 加购 → 结算 → 微信支付 → 到货通知 → 自提码核销 → 完成 / 售后退款；
团长 申请 → 审核通过 → 开团 → 群发裂变 → 分拣核销 → 看到佣金记账；
运营商 在 ops-web 里管商品、活动、团长、订单，无需开发介入即可日常运营；
全端 4 套皮肤 × 明暗切换即时生效。

---

## 2. 当前架构分析

- **空仓库**：`ai-shop/` 目前只有 `docs/`。C 端需新建 `c-app/`，与后续 `backend/`、`ops-web/` 平级。
- **可直接复用的已验证资产（powerbank）**：
  - `c-app/src/api/` 的**「唯一契约 + mock↔真实一键切换」**（`contract/mock/http/http-client/index`）——原样复刻
  - `c-app/src/ports/` 端能力抽象 + 条件编译模式——原样复刻，端能力清单按电商替换
  - `c-app/src/design/tokens.ts` + `pb-theme-sheet.vue` 主题系统——复刻并扩展为 4 套电商皮肤
  - `uno.config.ts` UnoCSS 主题色指向 CSS 变量的接法——原样复刻
  - Capacitor 6 出 Android/iOS 的工程配置——原样复刻
- **不复用**：powerbank 的业务域（借还/柜机/免押）、ar/en RTL（一期单语 zh-CN，保留 i18n 骨架）、nearpay（改微信支付）。

---

## 3. 方案设计

### 3.1 技术栈与版本（锁定 · 2026-08-05 实测 npm registry）

| 关注点 | 包 | 版本 | 说明 |
|--------|-----|------|------|
| 框架 | `@dcloudio/uni-app` 等全家桶 | **`3.0.0-5010520260709002`** | Vue3 线**稳定通道**最新（2026-07-10）。见下方「为什么不用 alpha」 |
| 类型 | `@dcloudio/types` | **`3.4.31`** | ⚠️ uni-app peer **精确要求 3.4.31**，不能取 latest 3.4.32 |
| 视图层 | `vue` | `^3.5.41` | — |
| 构建 | `vite` | **`5.2.8`（精确锁定）** | ⚠️ **不能升**，见下方硬约束 |
| UI 组件 | `wot-design-uni` | `^1.14.0` | peer `vue >=3.2.47` ✓ |
| 原子类 | `unocss` + `unocss-applet` | `66.7.5` + `^0.13.8` | ⚠️ applet peer 是 `~66.7.5`，**两者必须成对锁** |
| 状态 | `pinia` | `^4.0.2` | peer `vue ^3.5.11` / `typescript >=5.6` ✓ |
| 持久化 | `pinia-plugin-persistedstate` | `^4.7.1` | peer `pinia >=3.0.0` ✓ |
| i18n | `vue-i18n` | `^11.4.8` | zh-CN 单语，骨架保留 |
| 样式 | `sass` | `^1.102.0` | — |
| 类型检查 | `typescript` + `vue-tsc` | **`~5.9.3`** + `^3.3.9` | ⚠️ **不取 TS 7.0.2**，见下方 |
| App 壳 | `@capacitor/*` | `^8.5.0` | 需 **Node >= 22** |

### 三处「不能盲目取 latest」的硬约束

**1. Vite 必须锁 5.2.8 —— 这是 uni-app 自己的硬要求，不是保守**

```
npm view @dcloudio/vite-plugin-uni@vue3 peerDependencies
→ { vite: '5.2.8' }        # 精确版本，不是 ^5 或 >=5
```

Vite 当前 latest 是 **8.2.0**，但 uni-app 的 Vite 插件 peer 精确钉死 `5.2.8`。强升会在编译期直接炸（插件 API 已多次 break）。powerbank 也是这么锁的，属于同一个上游约束。**这一项等 DCloud 官方跟进 Vite 版本才能动。**

**2. TypeScript 取 5.9.3 而不是 latest 7.0.2**

TS 7 是原生重写版（Go），发布不久，`vue-tsc 3.3.9` 与 uni-app 工具链（构建在 Vite 5.2.8 时代）均未验证与 TS7 的兼容性。而 pinia 只要求 `>=5.6`，5.9.3 完全够用。
建议：**一期用 5.9.3**，二期单独起一个 spike 验证 TS7 再升，不要在项目起步期承担这个风险。

**3. uni-app 取稳定通道而非 alpha 通道**

```
@dcloudio/uni-app dist-tags:
  latest = 2.0.2-5010520260709001    ← Vue2 线，不要用
  vue3   = 3.0.0-alpha-5020320260803001  (2026-08-04, alpha)
  稳定    = 3.0.0-5010520260709002       (2026-07-10)  ← 采用
```

⚠️ 注意 `latest` 标签指向的是 **Vue2 线**，直接 `npm i @dcloudio/uni-app` 会装错。
`vue3` 标签当前指向 alpha（两天前发布）。**一期取稳定通道 `3.0.0-5010520260709002`**，只差一个月版本，但避免把 alpha 的坑带进项目起步期。

> 上述三项都记入 `package.json` 注释与 CI 校验，避免后人"顺手升级"踩坑。

### package.json（一期）

```jsonc
{
  "dependencies": {
    "@dcloudio/uni-app":        "3.0.0-5010520260709002",
    "@dcloudio/uni-app-plus":   "3.0.0-5010520260709002",
    "@dcloudio/uni-components": "3.0.0-5010520260709002",
    "@dcloudio/uni-h5":         "3.0.0-5010520260709002",
    "@dcloudio/uni-mp-weixin":  "3.0.0-5010520260709002",
    "@capacitor/core":    "^8.5.0",
    "@capacitor/android": "^8.5.0",
    "@capacitor/ios":     "^8.5.0",
    "vue":            "^3.5.41",
    "pinia":          "^4.0.2",
    "pinia-plugin-persistedstate": "^4.7.1",
    "vue-i18n":       "^11.4.8",
    "wot-design-uni": "^1.14.0"
  },
  "devDependencies": {
    "@dcloudio/types":            "3.4.31",   // 精确：uni-app peer 要求
    "@dcloudio/uni-cli-shared":   "3.0.0-5010520260709002",
    "@dcloudio/vite-plugin-uni":  "3.0.0-5010520260709002",
    "@capacitor/cli": "^8.5.0",
    "vite":       "5.2.8",      // 精确锁定：vite-plugin-uni peer 钉死此版本，勿升
    "typescript": "~5.9.3",     // 暂不取 TS7，待 vue-tsc/uni-app 验证
    "vue-tsc":    "^3.3.9",
    "unocss":         "66.7.5", // 与 unocss-applet 成对锁（applet peer ~66.7.5）
    "unocss-applet":  "^0.13.8",
    "sass": "^1.102.0"
  },
  "engines": { "node": ">=22" }  // Capacitor 8 要求
}
```

### 3.2 目录结构

```
c-app/src/
├── api/
│   ├── contract.ts       # interface ShopApi（唯一契约）
│   ├── mock.ts           # mockApi
│   ├── http.ts           # httpApi（对齐 /mp/**）
│   ├── http-client.ts    # Result<T> 拆包 + Bearer + 幂等 key
│   └── index.ts          # api = USE_MOCK ? mock : http
├── types/                # 契约镜像：OrderStatus/AfterSaleStatus/CategoryType/LeaderStatus
├── mock/db.ts            # 全域 mock 数据集（社区/团长/商品/订单/活动）
├── stores/               # user / community / cart / order / leader / theme / app
├── ports/                # auth · payment · share · poster · location · scan · push · media · theme
├── strategies/
│   ├── fulfillment/      # pickup(自提) · express(快递) · storeVerify(到店核销)
│   └── pricing/          # fixed(标品) · weighed(生鲜约重) · service(按次)
├── i18n/locale/zh-CN.ts
├── design/tokens.ts      # SKINS(4) / MODES / radius / spacing
├── shared/               # constants(零硬编码) + format(金额/重量/时间/距离)
├── components/app-overlay.vue   # 应用常驻层（飞入小球）；sh-* 基础件已移入 packages/ui
├── components/biz/       # 商品卡 / 规格选择器 / 收银台 / 自提码 / 分拣行 / 拼团条
└── pages/                # 见 §3.6
```

### 3.3 请求层与一键切换

```ts
// src/api/index.ts
export const api: ShopApi =
  import.meta.env.VITE_USE_MOCK !== '0' ? mockApi : httpApi
```

页面统一 `import { api } from '@/api'`，**零 `if (USE_MOCK)`**。一期先用 mock 把全部 UI 跑通，再整体翻转到真实 `/mp/**`。

契约口径（沿用 powerbank，待确认项 #5）：
- 响应包 `{ code, msg, data }`；分页 `{ records, total, page, size }`
- camelCase；单号 `xxxNo`；时间 `xxxAt`；枚举大写下划线
- 鉴权 `POST /mp/user/login {grantType}` → Bearer(realm=CONSUMER)；**无 RBAC，仅属主鉴权**
- **禁止 `delete*`**，软删除用 `archive*` / `unarchive*`

### 3.4 端能力抽象 `ports/`

| port | 小程序 | App | 一期用途 |
|------|--------|-----|---------|
| `auth` | 微信 openid/unionid | 手机号 OTP / 微信 / Apple | 登录建户 |
| `payment` | 微信支付 JSAPI | 微信 App SDK | 一期只接微信支付；支付宝二期 |
| `share` | `onShareAppMessage` / 朋友圈 | 微信开放平台（◐） | **裂变主入口** |
| `poster` | 小程序码 + Canvas 合成 | 同 | 分享海报，带 `leaderNo`/`inviterNo` |
| `location` | 微信定位 + 腾讯地图 | 高德/腾讯 SDK | 选社区、服务范围 |
| `scan` | `wx.scanCode` | Capacitor 扫码 | 团长核销台 |
| `push` | 订阅消息 | APNs/FCM/厂商通道 | 到货/发货/成团 |
| `media` | 微信选图 | Capacitor Camera | 售后凭证 |
| `theme` | 挂 `page` 根节点 | 挂 `documentElement` | 换肤属性写入差异 |

> **页面永远不写 `#ifdef`**，条件编译只在 `ports/` 内部。

### 3.5 品类策略（一期就位的扩展点）

一条交易主干，两个策略位按 `categoryType` 分发：

| | 日用品 `GOODS` | 生鲜 `FRESH` | 服务 `SERVICE` |
|---|---|---|---|
| **PricingStrategy** | `fixed` 固定价×数量 | `weighed` 约重估价 → 实称多退少补 | `service` 按次/套餐 |
| **FulfillmentStrategy** | `express` 快递 / `pickup` 自提 | `pickup` 预售批次 + 自提码 | `storeVerify` 到店核销码 |
| 一期实现 | ✓ | ✓ | ✓（**仅到店核销**，上门+预约排期二期） |

接口位：
```ts
interface PricingStrategy  { estimate(items): Amount; settle(order, actual): Adjustment }
interface FulfillmentStrategy { plan(order): Plan; verify(code): Result; track(order): Timeline }
```

### 3.6 页面清单（一期）

```
splash · login · bindPhone
community/    选社区 · 选自提点 · 切换
home/         首页(今日团) · 频道(生鲜/日用品/服务)
category · search
goods/        标品详情 · 生鲜详情 · 服务详情
cart
order/        确认订单(自提/快递/服务) · 收银台 · 支付结果
orders/       列表 · 详情 · 自提码 · 物流 · 售后申请 · 售后进度
activity/     拼团(开团/参团/团详情) · 邀请有礼 · 领券中心 · 新人专区
leader/       招募页 · 申请 · 审核状态
  └ console/  概览 · 分拣单 · 核销台 · 群发助手 · 佣金明细
message · kefu
me/           个人中心 · 地址簿 · 收藏足迹 · 外观设置 · 帮助中心
```

**tabBar（5）**：首页 · 分类 · 团长/招募（按身份切换） · 购物车 · 我的

### 3.7 主题系统

`design/tokens.ts` 定义 `SKINS`（`fresh`/`promo`/`mono`/`blue`）与 `MODES`（`light`/`dark`/`auto`）；
CSS 变量值定义在 `App.vue` 全局非 scoped 样式，按 `[data-skin][data-theme]` 组合选择器给值；
`uno.config.ts` 主题色指向 `var(--sh-*)`；
`packages/ui` 的 `stores/theme.ts` persist 用户偏好（两端共用同一份），`ports/theme.ts` 处理端差异写入根节点。

### 3.8 配置项（零硬编码）

| 配置 | 位置 |
|------|------|
| mock 开关 / API base | `.env`：`VITE_USE_MOCK` / `VITE_API_BASE` |
| 地图 key | `.env`：`VITE_MAP_KEY` |
| 业务常量（限购/截单/退款时限/起提额） | `shared/constants/` |
| 皮肤与模式 | `design/tokens.ts` |
| 端点路径 | `api/http.ts` 集中常量 |

---

## 4. 测试策略

| 层 | 覆盖 |
|----|------|
| 单测 | `strategies/pricing`（约重多退少补、限购、券叠加）· `strategies/fulfillment`（状态迁移）· `shared/format`（金额/重量/时间）· 归因优先级函数 |
| 规范测试 | `components/` 无 hex/rgb/oklch；圆角仅五档；页面无 `#ifdef`（grep 断言） |
| mock 状态机 | 非法状态迁移必须抛错；mock 真改 `db`，重开可读回 |
| 端到端手测 | H5 → 微信小程序真机 → Android/iOS 各跑一遍主闭环 |

必测场景：
1. 生鲜预售截单后不可下单、不可加购
2. 约重商品实称后差价原路退回，订单金额与明细一致
3. 拼团成团/超时失败自动全额退款
4. 分享链路带归因参数，新客下单后佣金归属正确
5. 自提码核销一次成功、二次报"已核销"
6. 切换社区后购物车不可售商品被移除且有二次确认
7. 4 套皮肤 × 明暗切换，全页面无写死色残留

---

## 5. 风险与注意事项

| # | 风险 | 应对 |
|---|------|------|
| 1 | **裂变归因规则未定** | 阻塞项。必须先定「邀请人 vs 团长 vs 渠道」优先级与窗口期，否则佣金必然扯皮 |
| 2 | 生鲜称重差价的退款通道 | 一期建议**原路退款**，避免余额账户带来的资金合规问题 |
| 3 | 逾期未自提规则 | 一期建议**顺延次日 + 超时作废**，规则页明示 |
| 4 | 小程序订阅消息一次性授权 | 需在下单成功页、开团页等关键节点收集，避免到货时无法触达 |
| 5 | WebView 长列表性能 | 商品列表用虚拟滚动 / 分页；首屏图片懒加载 |
| 6 | 团长佣金一期只记账不提现 | 需在团长端明确文案，避免预期落差 |
| 7 | Android 推送保活 | 一期 App 为 P1，可先只接 FCM + 应用内消息，厂商通道二期 |

---

## 6. 实现任务

### M0 工程地基 ✅（2026-08-05 完成）
- [x] `c-app/` 脚手架：uni-app CLI + Vite + TS + UnoCSS + wot-design-uni
- [x] 请求层：`contract/mock/http/http-client/index` + `.env` 一键切换
- [x] `stores/`（user/community/cart/theme）+ persist
- [x] `design/tokens.ts` + App.vue 变量 + `uno.config.ts` + 外观面板
- [x] `ports/` 骨架（8 个 port + 条件编译）
- [x] `strategies/` 接口位 + 三套 pricing + 三套 fulfillment 实现
- [x] tabBar + 登录 + 选社区 + 首页 + 分类 + **商品详情（标品/生鲜/服务 三态）** + 购物车 + 我的
- [x] **扁平色块设计语言**：去描边去阴影，公共积木 `sh-card` / `sh-chip` / `sh-btn` / 排版类下沉到 App.vue
- [x] **三语（中/英/阿）+ 阿语 RTL 整体镜像**，含原生 tabBar 文案与导航栏标题的运行时本地化
- [x] 验证：`vue-tsc` 零错误 · `build:h5` 通过 · `build:mp-weixin` 通过（无循环依赖警告）· H5 实测走通「选自提点 → 今日团 → 商品详情 → 加购 → 换肤 → 三语切换（含 RTL）」

**M0 期间修掉的两个真 bug（已写入 c-app/README「两个已踩过的坑」）**：
1. 主题变量同时声明在 `page` 与 `.sh-root` 上，把 `:root[data-theme]` 的覆盖挡掉 → **换肤静默失效**，构建与类型检查全绿、肉眼看不出来，只有实际切换才暴露。
2. CSS 注释里写 `skin-*` 与 `mode-*` 的斜杠连写形式会提前闭合注释，PostCSS 报 `Unexpected '/'`，整份全局样式编译失败。

**三语引入后又修掉的两个真 bug**：
3. 切语言后，已持久化的服务端文案（社区/自提点/购物车）仍是旧语言 → 页面中英阿混排。修法：切语言时重拉（`community.refreshLocalized()` + `cart.load()`），mock 侧也在「出口」处按当前语言本地化，对齐真实后端的 `Accept-Language` 行为。
4. RTL 下 `-25%` 显示成 `25%-` —— 数值是 LTR 序列，跟着 RTL 走符号会被甩到另一端。修法：`sh-num` 加 `direction: ltr; unicode-bidi: isolate;`，所有数值文本统一挂这个类。

**M0 遗留**：原生 tabBar / 导航栏 / 标题都不吃 CSS 变量与 i18n，只能运行时 API 改写
（`setTabBarStyle` / `setTabBarItem` / `setNavigationBarTitle`），代价是色板要在 `design/tokens.ts`
里以 `SKIN_HEX` / `MODE_HEX` 再维护一份，且 tabBar 文案顺序与 `pages.json` 强耦合（规范测试比对待 M1 补）。

**范围提示**：三语落到后端就是商品/类目/活动的多语言建模 + 运营端多语言录入，属于一期范围的实质扩大，见需求 §六 R9/R10。

---

### M0+ 追加（2026-08-05）：货币时区 · 商品模型扩展 · UI 二轮

- [x] **多货币**：金额一律最小货币单位整数流转；`CURRENCIES` 配 `minorUnits` 与符号位置；订单货币创建时锁定
- [x] **时区**：时间戳 UTC 流转，按市场时区渲染；生鲜截单按「市场本地 21:00」换算
- [x] **多规格 SKU 矩阵**：`specGroups` × `optionValues`，不可组合的取值探测后置灰
- [x] **虚拟商品**（`VIRTUAL`）与**卡券**（`CARD`）：新增 `instant` 履约策略，支付成功即发码 / 入卡包
- [x] **预约**（`APPOINTMENT`）：日期 × 时段选择，时段余量，改期规则常量化
- [x] **字体**：Inter（拉丁）+ Noto Kufi Arabic（阿语）可变字体，中文用系统字体
- [x] **自定义底部菜单**：字号从原生锁死的 ~20rpx 提到 28rpx，跟随皮肤与三语，带购物车角标
- [x] **加入购物车动效**：小球从点击处飞向购物车图标

**又修掉的两个真 bug**：
5. `tabBar.custom` 只有微信小程序认，H5/App 仍渲染原生 tabBar 并盖住自定义的 —— 底部出现**两套菜单**，且原生那套是静态中文。修法：保留 tabBar 配置（`switchTab` 依赖），条件编译隐藏原生并清掉其底部占位。
6. 加购动效**静默失效**：`biz-goods-card` 的 `add` 事件没透传原始 tap 事件，页面里 `$event` 是 `undefined`，`tapPoint` 抛错被 catch 吞成一个 toast —— **加购本身正常，只有动效不见**，日志里什么都看不到。修法：事件透传 + `tapPoint` 对空事件回落到屏幕中心而非抛错。

---

### M0+ 二轮修正（2026-08-05）

- [x] **详情页加购物车入口**：详情页不是 tab 页，没有底部菜单，加完购原本没有任何落点与反馈
- [x] **购物车结算条被菜单遮挡**：悬浮条的 bottom 只算了安全区没算菜单高度。菜单高度提为 CSS 变量 `--sh-tabbar-h`，各页面统一引用，不再各写各的数字
- [x] **mock 数据落盘**：购物车/订单/卡包写 storage，刷新后还在
- [x] **底部菜单换真图标**：内联 SVG 作 CSS mask，颜色吃 CSS 变量；线性/实心两态
- [x] **动效落点对准购物车**：两处落点（菜单 / 详情页操作条）各自上报实际位置，落地时图标弹跳

**这一轮又修掉的三个 bug**（全部是「看着差一点」型，日志无痕）：
7. **动效落点系统性偏移 44px**：`createSelectorQuery` 返回页面坐标，飞行层是 fixed 的视口坐标，H5 端差一个导航栏高度（`windowTop`）。
8. **量元素早于元素存在**：详情页操作条挂在 `v-if="goods"` 下，`onMounted` 时商品未加载，量不到就静默退回兜底落点。
9. **原生 tabBar 与自定义并存**（上一轮已修，此处补记）：`tabBar.custom` 只有小程序认。

---

### M0+ 三轮：多商家与评价（2026-08-05）

- [x] **数据模型按多商家建**：`merchantNo` 贯穿商品/订单/评价，一期数据全挂平台自己名下
- [x] 商品卡 + 详情页展示商家；商家信息条可进店
- [x] **商家详情页**：资质、评分（总分 + 三维度 + 依据）、在售商品、全部评价
- [x] **评价**：列表、晒图、商家回复、点赞（点赞态落盘）、排序（有图 → 高赞 → 最新）
- [x] 「我的」增加商家入驻入口（一期只有入口与说明）
- [x] 形态决策落档：[ADR-001 商家端形态与拆分时机](./ADR/ADR-001-商家端形态与拆分时机.md)

---

### M0+ 四轮：搜索 · 我买过的商家 · 邻里拼团 · 送货上门（2026-08-05）

- [x] **搜索**：商品 + 商家分 tab（不混排），搜索历史，商家搜索匹配「名称 + 简介 + 标签」
- [x] **我买过的商家**：从订单聚合（不另存关系表），按最近下单倒序
- [x] **邻里集单拼团**：自提点为成团单位、阶梯价、已参团同享最终档、不成团降级原价发货。设计见需求 §五之四
- [x] **送货上门**（`DELIVERY` 履约 + 配送费规则），一期自提为主
- [x] 结算决策落档：[ADR-002 结算走微信支付分账](./ADR/ADR-002-结算走微信支付分账.md)

**类型系统这轮帮了个忙**：`Record<FulfillmentType, FulfillmentStrategy>` 的穷尽性检查在编译期
直接拦下「新增 DELIVERY 但忘了注册策略」—— 这类遗漏在运行时会退化成静默走了兜底策略，很难发现。

**拼团价格不落库**：档位与价格由 `GROUP_BUY` 规则实时推导。存下来就会与规则漂移，
规则是唯一真源。

---

### M0+ 五轮：团购重新定位（2026-08-05）

**定位修正**：之前把拼团当增长引擎设计（阶梯价、集单成本论），过度了。
团购只是一种活动 —— 阶梯价已砍掉改单档，首页楼层已撤掉，收进独立入口「我的 → 邻里团购」。

- [x] **商家团**（B 发起）：单档成团、自提点为范围、成团差价退回、不成团降级原价发货
- [x] **邻里求团**（C 发起 → 多 B 报价）：发需求 → 邻居 +1（意向，不涉资金）→ 多商家报价 → 发起人对比选定
- [x] 两条线**分开建模、分 tab 展示**，不复用 —— 求团发起时商品还不存在，塞进商家团模型会两头不讨好
- [x] 报价按价格从低到高排；起订量未达时不可选定
- [x] **买 N 送 M** 全链路：详情页促销标签 + 实时「可获赠 N 件」提示、购物车赠品行、下单生成价格为 0 的赠品行
- [x] **报价防加价**（不审核）：锁价 + 改价公示 + 毁约计数，三层都在报价卡上显式展示 —— 见 [ADR-003](./ADR/ADR-003-报价不审核而用锁价公示信用防加价.md)
- [x] **+1 二次确认**：选定报价后每人各自确认才算下单，展示「N/T 人已确认」

**新增待办（需业务/后端确认）**：

| # | 事项 | 说明 |
|---|------|------|
| T1 | **商品按市场定价** | mock 现在用固定汇率换算，真实系统必须按市场分别定价（汇率换算出来的价格不能直接卖） |
| T2 | **运费规则多市场化** | `fixed.ts` 里的起步价与包邮门槛目前是常量，应由后端按市场下发 |
| T3 | **时区方案上限** | 固定 UTC 偏移仅对无夏令时市场成立（中国 +8 / 海湾 +4）。进欧美市场必须换带 tz 数据的方案 |
| T4 | **卡券核销与储值扣减** | 一期只做「买到手、入卡包」，核销扣次/扣额度属于 M1 |
| T5 | **虚拟商品发码对接** | 现在是 mock 生成码，真实需要对接卡密库存与发放风控 |
| T6 | **字体自托管** | gstatic 在中国大陆不稳定 + 小程序域名白名单，生产前必须迁到自有 CDN |
| T7 | **分账参数书面口径** | 分账比例上限、分账时限、个人接收方限额 —— 各签约模式不同，不能按经验值写死（ADR-002 §3） |
| T8 | **按商家拆单** | 一个购物车跨商家时要拆成子订单，分账以子订单为单位（ADR-002 §5） |
| T9 | **退款回退分账** | 已分账订单的退款必须先回退分账，退款链路要把这步算进去 |
| T10 | **成团差价退回时机** | 截单即退 vs 履约完成后退（需求 §五之四 G2） |
| T11 | ~~买赠口径~~ | **已定：每满 N 件送 M 件**（买 2 送 1 买 4 件送 2），与商家口头承诺一致 |
| T14 | **毁约判定与阈值** | 选定后多久不发货算毁约？几次限制报价？（ADR-003 §5） |
| T15 | **恶意低价刷单** | 商家自己找人 +1 凑团再毁约以打击竞对，需风控识别 |
| T12 | **求团转正式团** | 选定报价后如何生成商品与团；+1 的邻居是否需二次确认下单（建议需要） |
| T13 | **报价是否需审核** | 防止商家低价引流后加价 |

### M1 交易闭环（全 mock）
- [ ] 首页/频道/分类/搜索
- [ ] 三态商品详情 + 规格选择器
- [ ] 购物车（按履约方式分组）
- [ ] 结算三条线 + 收银台（支付 Stub）
- [ ] 订单列表/详情/时间线/自提码
- [ ] 售后申请与进度
- [ ] 小程序真机跑通

### M2 活动 + 裂变
- [ ] 券/满减/限时/新人礼
- [ ] 拼团全流程
- [ ] 分享卡片 + 海报 + 邀请有礼 + 归因

### M3 团长
- [ ] 招募/申请/审核状态
- [ ] 经营台：概览/分拣单/核销台/群发助手/佣金明细

### M4 接真后端 + App 上架
- [ ] 翻转 `VITE_USE_MOCK=0`，对齐 `/mp/**`
- [ ] 微信支付真付
- [ ] Capacitor 出包，Android/iOS 上架

---

## 7. 开工前需确认

1. **裂变归因优先级与窗口期**（阻塞 M2）
2. 逾期未自提规则、称重差价退款通道
3. 是否沿用 powerbank 的 `{code,msg,data}` 契约口径
4. 后端是否同步起 `backend/`（Java 21 + Spring Boot 4 模块化单体），还是 C 端先跑 mock

---
确认记录：待用户确认
