# ai-shop 整体架构与技术栈

> 状态：草稿（**待确认**）· 创建 2026-08-05
> 关联需求：[../requirements/C端功能清单.md](../../requirements/C端功能清单.md)
> 参考工程：`powerbank`（ShareHub）—— C 端载体、请求层、ports 抽象、后端分层直接沿用其已验证地基。

---

## 1. 定位与约束

社区电商 C 端：**微信小程序（P0）+ Android/iOS App（P1）**，一套代码。
业务核心是**社区团长孵化 + 社交裂变**，品类覆盖日用品、生鲜水果、服务。

硬约束：
1. 一套码同时出小程序 + App + H5（H5 用于分享落地页与预览）
2. 裂变依赖微信生态（分享卡片、小程序码、订阅消息），端能力差异必须被隔离
3. 三品类履约模型不同，交易主干只能有一条
4. 复用 neargo/powerbank 已验证的工程地基，不重造轮子

---

## 2. 总体架构

```
┌────────────────┐  ┌────────────────┐  ┌────────────────┐
│  微信小程序 P0  │  │  App(iOS/And) P1│  │  H5 分享落地页  │
└───────┬────────┘  └───────┬────────┘  └───────┬────────┘
        └──────── 同一套 uni-app 代码（条件编译） ────────┘
                             │  Bearer(C 池) · /mp/**
                    ┌────────▼────────┐
                    │   C 端 BFF      │   聚合裁剪，端无关
                    └────────┬────────┘
        ┌───────────┬────────┼────────┬───────────┬──────────┐
    用户/认证    商品/搜索   交易/履约  营销/裂变   团长/成长   结算/账务
        └───────────┴────────┴────────┴───────────┴──────────┘
                    模块化单体（Spring Boot，可裂解为微服务）
                             │
              MySQL/MariaDB · Redis · 对象存储 · MQ · ES(可选)
```

同一份后端同时服务运营端（ops-web）与 C 端；C 端端点前缀统一 `/mp/**`。

---

## 3. C 端技术栈（对齐 powerbank/c-app）

| 关注点 | 选型 | 说明 |
|--------|------|------|
| 框架 | **uni-app（Vue3 + TypeScript + Vite，CLI 工程）** | 非 HBuilderX 工程，保证 git/lint/tsc/CI |
| UI 组件 | **wot-design-uni** | 跨端组件齐全，与 powerbank 一致 |
| 原子 CSS | **UnoCSS + preset-wind + unocss-applet** | Tailwind 兼容类名，与运营端心智统一 |
| 状态管理 | **Pinia**（+ `pinia-plugin-persistedstate`） | 登录态/购物车/社区归属持久化 |
| 请求层 | `uni.request` 封装 + **mock↔真实一键切换** | 见 §5，powerbank 已验证 |
| 路由 | uni-app `pages.json` + tabBar | — |
| i18n | vue-i18n | 单语 zh-CN 起步，**保留骨架**便于后续出海 |
| 设计 token | `src/design/tokens.ts` + CSS 变量 | 跨端 SSOT，组件层禁写死颜色 |
| **UI 风格切换** | `data-skin` × `data-theme` 挂根节点 | 皮肤(4 套) × 明暗(浅/深/跟随)，见 §6.5 |
| App 壳 | **Capacitor 6** | `dist/build/h5` 作为 webDir，出 Android/iOS |
| 类型检查 | `vue-tsc --noEmit` | CI 拦截 |

**具体版本锁定见 [TDD-c-app.md §3.1](../design/TDD-c-app.md#31-技术栈与版本锁定--2026-08-05-实测-npm-registry)。**
三条不能盲目取 latest 的硬约束：**Vite 必须精确 5.2.8**（uni-app 插件 peer 钉死）、**TypeScript 取 5.9.3 不取 7.x**（工具链未验证）、**uni-app 取稳定通道不取 alpha**（且 `latest` 标签指向 Vue2 线，会装错）。

### 为什么不是别的方案

| 方案 | 结论 |
|------|------|
| **uni-app（Vue3+Vite）** | ✅ 采用。小程序是 P0 硬需求，成熟度与生态最匹配；团队已有 powerbank 存量经验 |
| uni-app x（UTS 原生渲染） | ❌ 小程序编译路径与组件生态不齐，风险不接受 |
| Flutter + 独立小程序 | ❌ 小程序需另写一套，裂变主战场的代码不复用，成本翻倍 |
| Taro（React） | ❌ 与 powerbank/Vue 存量经验不一致，且 App 端需另配 RN 或 WebView 壳 |
| 原生双端 + 小程序 | ❌ 三套码，MVP 阶段不成立 |

### App 壳选型：Capacitor vs uni-app 原生打包

沿用 powerbank 的 **Capacitor**：可控的原生工程（`android/`、`ios/` 在仓库里）、插件生态成熟、CI 可自动化，不依赖 HBuilderX 云打包。
代价：App 端为 WebView 渲染，长列表与复杂动效需要针对性优化。社区电商以列表 + 表单为主，可接受。
若后续 App 成为主战场且性能吃紧，再评估关键页面原生化。

---

## 4. 目录结构（`c-app/`）

```
ai-shop/
├── c-app/                      # C 端（小程序 + App + H5）
│   ├── src/
│   │   ├── api/                # ★ 唯一契约 + mock↔真实一键切换
│   │   │   ├── contract.ts     #   interface McpApi
│   │   │   ├── mock.ts         #   mockApi（先行，脱离后端出全部 UI）
│   │   │   ├── http.ts         #   httpApi（对齐 /mp/**）
│   │   │   ├── http-client.ts  #   uni.request 封装：Result<T> 拆包 + Bearer + 幂等 key
│   │   │   └── index.ts        #   export const api = USE_MOCK ? mock : http
│   │   ├── types/              # 契约镜像（OrderStatus/AfterSaleStatus 等）
│   │   ├── mock/db.ts          # 全域 mock 数据集 + paginate/CRUD helper
│   │   ├── stores/             # user / community(社区归属) / cart / order / leader / app
│   │   ├── ports/              # ★ 端能力抽象（条件编译隔离，见 §6）
│   │   ├── strategies/         # ★ 品类扩展点：履约策略 + 计价策略（见 §7）
│   │   ├── i18n/               # zh-CN（骨架保留多语）
│   │   ├── design/tokens.ts    # 设计 token
│   │   ├── shared/             # constants（零硬编码）+ 金额/重量/时间格式化
│   │   ├── components/         # 业务复用件：商品卡/规格选择器/收银台/自提码/分拣行
│   │   └── pages/              # 见 §8 页面清单
│   ├── uno.config.ts / vite.config.mts / tsconfig.json / .env.*
│   ├── capacitor.config.ts / android/ / ios/
│   └── package.json
├── backend/                    # 模块化单体（见 §9）
├── ops-web/                    # 平台运营端（Next.js 16 + Tailwind 4，见 TDD-ops-web.md）
└── docs/
```

---

## 5. 请求层：mock ↔ 真实一键切换（核心地基）

复刻 powerbank 已验证模式。页面统一 `import { api } from '@/api'`，**零 `if (USE_MOCK)`**：

```ts
// src/api/index.ts
export const api: McpApi =
  import.meta.env.VITE_USE_MOCK !== '0' ? mockApi : httpApi
```

```bash
# .env
VITE_USE_MOCK=1            # 默认走内存 mock，UI 脱离后端跑通
# VITE_USE_MOCK=0
# VITE_API_BASE=http://localhost:8080
```

价值：C 端 UI 与后端并行开发，MVP 阶段先用 mock 把 22 个模块的交互跑通，再整体翻转到真实 `/mp/**`。

**契约口径**（与 powerbank 一致，避免两个项目两套心智）：
- 响应包：`{ code, msg, data }`
- 分页：`{ records, total, page, size }`
- 字段：camelCase，业务单号 `xxxNo`，时间 `xxxAt`，枚举大写下划线
- 鉴权：`POST /mp/user/login {grantType}` 换 Bearer(realm=CONSUMER)，**无 RBAC，仅属主鉴权**（后端防 IDOR）

---

## 6. 端能力抽象层 `ports/`（条件编译）

端差异全部收敛在这里，业务逻辑与 UI 共用。用 `#ifdef MP-WEIXIN / APP-PLUS / H5` 隔离。

| port | 小程序 | App | 说明 |
|------|--------|-----|------|
| `auth.ts` | 微信 openid/unionid 一键登录 | 手机号 OTP / 微信开放平台 / Apple 登录 | unionid 打通双端同一用户 |
| `payment.ts` | 微信支付 JSAPI | 微信 App SDK / 支付宝 SDK | 端侧不写死 PSP，以后端回调为准 |
| `share.ts` | ★ 分享卡片 `onShareAppMessage` / 朋友圈 | 微信开放平台分享（受限） | **裂变核心 port** |
| `poster.ts` | 小程序码 + Canvas 合成海报 | 同（或服务端合成） | 带 `leader_no` / `inviter_no` |
| `location.ts` | 微信定位 + 腾讯地图 | 高德/腾讯 SDK | 选社区、服务范围判定 |
| `scan.ts` | `wx.scanCode` | Capacitor 扫码插件 | 团长核销台 |
| `push.ts` | 订阅消息（一次性授权） | APNs / FCM / 国内厂商通道 | 触达策略见需求 §22 |
| `media.ts` | 微信选图/录像 | Capacitor Camera | 售后凭证、评价晒单 |
| `contact.ts` | 客服会话 | WebView 客服 | — |

> 原则：**页面永远不写 `#ifdef`**。所有条件编译只出现在 `ports/` 内部。

### 6.5 主题系统（UI 风格切换）

复刻 powerbank 已验证机制，**两个正交维度**：

```
根节点 <page data-skin="fresh" data-theme="light">
   ↓ CSS 变量定义在 App.vue 全局样式（非 scoped）
   --sh-primary / --sh-surface / --sh-text / --sh-tint ...
   ↓ UnoCSS 主题色指向 var(--sh-*)（uno.config.ts）
   ↓ 组件只用 token 类名，不写死颜色
换肤 = 改根节点属性 → 全局即时生效，零重载、零重渲染开销
```

| 维度 | 取值 | 存储 |
|------|------|------|
| `data-skin` | `fresh`(默认) / `promo` / `mono` / `blue` | Pinia `useThemeStore` + persist |
| `data-theme` | `light` / `dark` / `auto`(跟随系统) | 同上 |

- **默认皮肤可由运营端下发**（`GET /mp/config/theme`），用户手动选择后本地偏好优先
- 小程序端 `data-*` 挂在 `page` 根元素；App/H5 挂 `documentElement`，差异收进 `ports/theme.ts`
- 约束由单测拦截：`components/` 不许出现 hex/rgb/oklch；圆角只用 `sm/md/lg/xl/full` 五档

---

## 7. 品类扩展点：一条交易主干，两个策略位

三品类（日用品/生鲜/服务）**共用**下单、支付、订单、售后、佣金主干，差异只下沉到两个扩展点：

| 扩展点 | 标品 | 生鲜 | 服务 |
|--------|------|------|------|
| **FulfillmentStrategy**（履约） | 快递单号轨迹 / 自提码 | 预售批次 + 自提码 + 缺货处理 | 预约时段 + 核销码 / 上门打卡 |
| **PricingStrategy**（计价） | 固定价 × 数量 | **约重估价 → 实称调整（多退少补）** | 按次 / 按时长 / 套餐次卡 |

端侧对应 `src/strategies/{fulfillment,pricing}/`，按 `categoryType` 分发；后端对应领域服务的策略接口。
**MVP 只实现标品 + 生鲜两套策略，但接口位必须在 MVP 就位**，否则接入服务品类时是重构而非扩展。

---

## 8. 页面清单（MVP）

```
pages/
├── splash / login / auth(手机号绑定)
├── community/           选社区、选自提点、切换
├── home/                首页(今日团) · 频道(日用品/生鲜/服务)
├── category / search/
├── goods/               标品详情 / 生鲜详情 / 服务详情
├── cart/
├── order/               确认订单(自提/配送/服务) · 收银台 · 支付结果
├── orders/              列表 · 详情 · 自提码 · 物流 · 售后申请/进度
├── activity/            拼团 · 砍价 · 邀请有礼 · 新人专区 · 领券中心
├── leader/              ★ 招募页 · 申请 · 培训 · 考核
│   └── console/         ★ 经营台：概览 · 分拣单 · 核销台 · 群发助手 · 客户 · 佣金
├── wallet/              余额 · 佣金明细 · 提现
├── message / kefu/
└── me/                  个人中心 · 地址簿 · 收藏足迹 · 设置 · 帮助中心
```

**tabBar（5 tab）**：首页 · 分类 · 团长（身份可见，普通用户显示「招募」）· 购物车 · 我的

---

## 9. 后端技术栈（对齐 powerbank/backend）

| 关注点 | 选型 |
|--------|------|
| 语言/框架 | **Java 21 + Spring Boot 4.0.x**（继承 `ai.neargo:neargo-parent`） |
| 形态 | **模块化单体（modulith）**，`api` 接口层 + `svc-*` 实现层，可按需裂解为微服务 |
| ORM | MyBatis-Plus |
| 存储 | MySQL / MariaDB（主）· Redis（缓存/分布式锁/库存扣减）· 对象存储（图片/视频） |
| 消息 | MQ（订单超时关单、库存释放、佣金结算、通知下发） |
| 搜索 | 起步用 DB + 前缀索引；商品量级上来后接 ES |
| 认证 | auth-core，C 池 Bearer（realm=CONSUMER），属主鉴权防 IDOR |
| 支付 | 微信支付（小程序 JSAPI + App）、支付宝（App）；统一支付网关抽象 |

### 建议模块边界

> ⚠️ **本节已被 [TDD-backend](../design/TDD-backend.md) 取代**，那里有 Maven 模块划分、依赖规则、三层契约与越权防线；下表只保留域级概览。
> 变更点：`svc-leader` 已按 [ADR-004](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) 废止（团长角色不存在），其履约职责并入 `svc-fulfillment`。

| 模块 | 职责 |
|------|------|
| `svc-user` | 用户、登录、归属关系、地址簿、社区/自提点主数据、商家主体 |
| `svc-product` | 商品、SKU、类目、社区商品池、库存、门店主页、评价 |
| `svc-trade` | 购物车、下单拆单、支付、订单、售后 |
| `svc-fulfillment` | 履约策略、自提核销、分拣、配送、服务预约 |
| `svc-marketing` | 券、活动、团购与求团、**进店归因** |
| `svc-settle` | 分账、结算单、费率、提现、履约服务费 |
| `svc-message` | 订阅消息、推送、站内信、客服工单 |
| `svc-platform` | ops 账号与 RBAC、系统配置与开关、风控、报表 |

> **归因服务（`svc-marketing` 内）是本项目最容易失控的地方**：店铺码、邀请人、渠道三种归因必须有唯一优先级与可审计的关系链，建议单独建表 + 全链路留痕。

---

## 10. 工程与质量约定（沿用 powerbank 教训）

1. **零硬编码**：颜色走 token、金额/时间/重量走 `shared/format`、业务常量走 `shared/constants`
2. **组件层禁写死颜色**，圆角只用固定档位，由测试拦截
3. **页面禁写 `#ifdef`**，条件编译只在 `ports/`
4. **提交只用显式文件路径**，不用 `git add -A`（多会话并行仓库的既有教训）
5. **mock 必须真改 db**（重开能读回），状态机在 mock 层强制，非法迁移抛错
6. 契约**禁止 `delete*`**，软删除语义用 `archive*` / `unarchive*`

---

## 11. 里程碑建议

| 阶段 | 内容 | 产出 |
|------|------|------|
| **M0 工程地基** | 脚手架 + 请求层一键切换 + stores/ports/strategies/design + tabBar + 登录 | H5 跑通 |
| **M1 交易闭环（全 mock）** | 社区归属 → 商品 → 购物车 → 下单 → 支付 Stub → 订单 → 自提核销 | 小程序真机跑通 |
| **M2 生鲜专项 + 售后** | 预售截单、称重差价、缺货、坏果包赔、极速退 | 翻转真实后端 |
| **M3 团长孵化 + 经营台** | 招募→培训→考核→开团；分拣单/核销台/群发助手/佣金 | 团长可独立运营 |
| **M4 裂变增长** | 分享卡片/海报、邀请有礼、拼团砍价、归因与防作弊 | 增长闭环 |
| **M5 App 上架** | Capacitor 打包、推送通道、支付宝、应用商店合规 | Android/iOS 上架 |
| **M6 服务品类 + 分销/会员** | 服务策略接入、二级分销、付费会员 | 品类扩展 |

---

## 12. 待确认（阻塞技术方案定稿）

1. 端优先级：确认「小程序 P0 / App P1」（本方案基于此假设）
2. 是否复用 neargo 基础框架与 auth-core（决定后端起步速度）
3. 分销层级上限（合规，见需求 §六 R1）
4. 服务品类是自营还是撮合第三方（决定是否需要商家入驻与分账）
5. 是否沿用 powerbank 的 `{code,msg,data}` 契约口径，还是走 commons 的 `{code,message,data}`
