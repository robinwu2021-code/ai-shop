# TDD-ops-web（平台运营端脚手架）

> 状态：**已实现（脚手架部分）** · 创建 2026-08-05
> 关联需求：[需求矩阵-三端 §六 平台端矩阵](../requirements/需求矩阵-三端.md)（P-1.1 ~ P-17.1，17 个业务域）
> 关联架构：[architecture.md §4](./architecture.md)（`ops-web/` 目录位已预留）
> 参考工程：`~/work/ai/powerbank/ops-web`（Next.js 16 + React 19 + Tailwind 4 + TanStack Query，已实机验证）
> 回答待确认项：需求矩阵 §九 **M2「平台端从零建还是复用现有 ops-web」→ 复用 powerbank 地基**

---

## 1. 需求摘要

建平台运营端（ops-web，PC Web），承载需求矩阵 §六 的 17 个业务域（P-1 账号权限 … P-17 系统配置），
角色为需求矩阵 §2.3 的 11 类平台岗位（超管 / 商品运营 / 活动运营 / 社区运营 / 商家运营 BD / 审核 / 客服 / 财务 / 风控 / 数据 / 技术运维），
权限模型 **RBAC + 数据域**（`tenant_no=MAIN` / `merchant_no` / `community_no` / `pickup_no`）。

**本 TDD 只覆盖「脚手架」**：工程地基 + 设计 token + UI 组件层 + 导航/权限骨架 + 请求层一键切换 + 样板页。
各业务域的具体页面另立 TDD（`TDD-ops-商家治理.md` 等），本文档为它们提供地基与组件契约。

验收标准：
1. `npm run dev -w ai-shop-ops-web` 起站，登录页选角色进入，左侧 17 域导航按角色裁剪
2. `NEXT_PUBLIC_USE_MOCK=1` 下全站零后端可跑；置 0 即指向 `/ops/**`，页面零改动
3. `/dev/ui` 组件预览页列出全部原语与组合件，明暗 × 皮肤切换生效
4. `npm run check`（tsc + vitest）全绿，含 token 约束测试与导航结构测试

---

## 2. 当前架构分析

| 现有资产 | 路径 | 与本功能的关系 |
|---|---|---|
| C 端 | `c-app/`（uni-app + wot-design-uni + UnoCSS） | **技术栈不同，不共用组件**；共用契约口径与 token 语义 |
| B 端 | `b-app/`（同 C 端栈） | 同上 |
| 共享层 | `packages/shared/`（types / design tokens / utils / mock db） | ✅ **复用 `utils/{money,datetime,format}` 与 `types`**；`design/tokens.ts` 的皮肤色板作为 ops-web 主色的**同源真值** |
| 契约生成 | `c-app/scripts/gen-openapi.mjs` → `docs/api/openapi.yaml` | 同模式复制一份给 ops-web，产出 `docs/api/openapi-ops.yaml` |
| powerbank/ops-web | 外部仓库 | **提取源**：token 三层体系、37 个 UI 组件、外壳、请求层、导航机制 |

powerbank/ops-web 中**可直接搬**的与**必须重写**的边界（依据它自己的 `components/README.md` 分层）：

| 层 | 是否含充电宝业务语义 | 处置 |
|---|---|---|
| `app/globals.css` token 三层 | 否 | **逐字搬** |
| `components/ui/*`（原语 + 组合件，31 文件） | 否 | **逐字搬** |
| `components/layout/*`（rail / secondary-nav / header / theme·lang switcher / phase-guard） | 否（读 `lib/nav`） | **搬，接新 nav 数据** |
| `lib/{utils,notify,export-csv,import-csv,form-validate,phase}` | 否 | **搬** |
| `lib/stores/{theme,locale,nav-prefs}` | 否（storage key 需换前缀） | **搬 + 改 key** |
| `lib/api/{http-client,query,error}` + `contract/mock/http/index` 四件套模式 | 否（模式） | **搬骨架，契约重写** |
| `components/{status,archive,read-only-notice,quick-actions}.tsx` | **是**（订单/工单/仓位枚举） | 只搬 `archive`/`read-only-notice`（通用），`status` 按 ai-shop 枚举重写 |
| `lib/{nav,permissions,auth,types}` + `lib/mock/db/*` + `app/*/page.tsx` | **是** | **全部重写**（按需求矩阵 §六 / §2.3） |

---

## 3. 方案设计

### 3.1 方案选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 新建 `ops-web/`，分层提取 powerbank 地基，业务层按需求矩阵重写**（推荐） | 拿到已实机验证的 token 体系与 37 个组件；不夹带充电宝业务残留；一次性把分层规矩立住 | 需逐文件判断归属（本表已判完） | ✅ 采用 |
| B. 整仓复制 powerbank/ops-web 再删改 | 起步最快 | 充电宝枚举/mock/页面渗进 ai-shop，后续每个域都要边写边删，分层会被拖垮 | ❌ |
| C. `shadcn init` 从零搭 | 干净 | 丢掉 token 三层体系、`DataTable`/`FormDrawer`/`Toolbar` 等组合件与四类浮层的判据 —— 这些是 powerbank 试错四轮的产物 | ❌ |

### 3.2 技术栈（对齐 powerbank/ops-web，与 C/B 端刻意不同）

| 关注点 | 选型 | 说明 |
|---|---|---|
| 框架 | Next.js 16（App Router）+ React 19 | PC 后台，非跨端，不用 uni-app |
| 样式 | Tailwind 4 + CSS 变量三层 token | 与 C 端 UnoCSS 是**两套实现、一套语义** |
| 组件 | Radix Primitives + shadcn 风格自持组件 | 组件代码进仓，不锁上游 |
| 数据 | TanStack Query 5 + TanStack Table 8 | |
| 状态 | Zustand 5（theme / locale / nav-prefs / auth） | |
| 图表 | Recharts 2 | P-16.1 数据看板 |
| 测试 | Vitest 4 | 纯函数 + 契约 + token 约束 |

### 3.3 目录结构（`ops-web/`）

```
ops-web/
├── app/
│   ├── globals.css              ★ 搬：token 三层（原始/语义/组件）+ 皮肤 + 暗色 + 密度
│   ├── layout.tsx               ★ 搬：字体 + 首帧主题脚本（防闪）
│   ├── login/page.tsx           重写：11 角色 mock 登录
│   ├── page.tsx                 重写：工作台（P-16.1 骨架）
│   ├── merchants/page.tsx       样板页 ①：列表 + 筛选 + 审核抽屉（P-11.1）
│   ├── orders/page.tsx          样板页 ②：列表 + 详情抽屉（P-4.1）
│   └── dev/ui/page.tsx          ★ 搬：组件预览与 token 探针
├── components/
│   ├── ui/                      ★ 搬 31 个：原语 + 组合件（见 §3.4）
│   ├── layout/                  ★ 搬 6 个：app-shell / rail / secondary-nav / header / theme-switcher / lang-switcher
│   ├── status.tsx               重写：ai-shop 枚举徽标（订单/售后/审核/商家状态）
│   ├── archive.tsx              ★ 搬：软删除页面件（契约禁 delete*，与 C 端同规矩）
│   └── read-only-notice.tsx     ★ 搬
├── lib/
│   ├── api/{contract,mock,http,http-client,index,query,error}.ts   模式搬 + 契约重写（/ops/**）
│   ├── mock/db/*.ts             重写：按业务域切片，写操作真落库
│   ├── types/*.ts               重写：镜像 packages/shared/src/types + 平台端专有
│   ├── auth.ts / permissions.ts 重写：11 角色 × 权限码 × 数据域
│   ├── nav.ts                   重写：17 域 → L1 section（三级导航）
│   ├── phase.ts                 ★ 搬：一期/二期门禁（对齐矩阵 P0/P1/P2）
│   ├── i18n/                    ★ 搬骨架（见 §3.6）
│   └── {utils,notify,export-csv,import-csv,form-validate}.ts       ★ 搬
└── package.json / tsconfig.json / next.config.ts / vitest.config.ts / .env.local.example
```

`ops-web` 加入根 `package.json` 的 `workspaces`，脚本加 `dev:p` / `build:p`。

### 3.4 UI 组件层（提取清单，31 个文件）

**原语**：`button` `input`(Input/Select) `textarea` `label` `checkbox` `radio-group` `switch` `date-input` `badge`(BadgeTone 色调唯一真源) `card` `table` `tabs` `segmented.ts` `progress` `notice` `misc`(StatCard/EmptyState/Skeleton/PageTitle/Pagination) `tooltip` `separator` `avatar` `popover` `dropdown-menu`

**组合件**：`data-table` `form-drawer` `drawer`(含唯一的 `Field`) `toolbar` `tab-header` `confirm-dialog` `multi-select` `status-badge` `filter-select` `timeline` `tree` `toaster`

**不搬**：`site-map.tsx`（充电宝点位地图，ai-shop 的社区/自提点地图另做）

沿用 powerbank 的三条分层硬规矩，写进 `ops-web/components/README.md`：
1. `ui/` 内**不许出现业务词**；业务件不许被 `ui/` 引用
2. 颜色只用 token（禁 hex/rgb），圆角只用五档（`control/field/card/sheet/chip`）——由 `lib/design-tokens.test.ts` 拦截
3. 四类浮层按**内容性质**选：Tooltip(纯说明) / DropdownMenu(动作列) / Popover(顺手改一下) / Drawer(进去做一件事)

### 3.5 导航与权限（重写，本脚手架的业务骨架）

`lib/nav.ts` 以需求矩阵 §六 的 L2 业务域为 L1 section（下为初版，实现时逐行对照矩阵）：

| L1 section | module | 覆盖矩阵条目 |
|---|---|---|
| 经营看板 | `dashboard` | P-16.1 |
| 商品与类目 | `product` | P-3.1 / P-3.2 / P-3.3 |
| 交易订单 | `order` | P-4.1 / P-4.2 |
| 履约调度 | `fulfillment` | P-5.1 / P-5.2 |
| 售后治理 | `aftersale` | P-6.1 |
| 营销活动 | `marketing` | P-7.1 / P-7.2 / P-7.3 / P-7.4 |
| 团购与求团 | `group` | P-8.1 / P-8.2 |
| 增长与归因 | `growth` | P-9.1 / P-9.2 |
| 门店主页治理 | `store` | P-10.1 |
| 商家治理 | `merchant` | P-11.1 |
| 结算与资金 | `finance` | P-12.1 / P-12.2 |
| 评价治理 | `review` | P-13.1 |
| 消息与客服 | `message` | P-14.1 / P-14.2 |
| 素材与内容 | `content` | P-15.1 / P-15.2 |
| 社区与网点 | `community` | P-2.1 / P-2.2 |
| 风控 | `risk` | P-16.2 |
| 员工与权限 | `iam` | P-1.1 |
| 系统配置 | `system` | P-17.1 |

- 权限码沿用 `模块:对象:动作`（如 `merchant:apply:audit`、`finance:settle:execute`）
- **数据域**是 ai-shop 相对 powerbank 新增的一维：`lib/permissions.ts` 除 `can(role, perm)` 外增 `scopeOf(user) → {merchantNo?, communityNo?, pickupNo?}`，
  由请求层自动带上；**前端只做展示裁剪，越权拦截以后端为准**（矩阵 §2.3 注）
- `phase` 沿用「徽章」语义（P0/P1/P2 ↔ Phase 1/2/3），`ready` 表示前端静态功能已实机验证

### 3.6 i18n（M7 已定：保留骨架，只填中文）

平台端默认**仅中文**，但保留 powerbank 的 i18n 骨架（`t()` + messages 分文件 + RTL 处理），理由：
矩阵 P-3.2.5「多语言文案与翻译审核」与 P-17.1.2「语言回落规则」是运营端**要管理**的对象，
即使运营人员界面只用中文，文案编辑器仍要处理 zh/en/ar 三语字段。骨架留着，语言包只填 `zh`。

### 3.7 请求层（一键切换，与 C/B 端同一心智）

```ts
// lib/api/index.ts
export const api: OpsApi = process.env.NEXT_PUBLIC_USE_MOCK !== "0" ? mockApi : httpApi;
```

- 端点前缀 `/ops/**`（C 端是 `/mp/**`），响应包 `{code,msg,data}`，分页 `{records,total,page,size}`
- 鉴权 Bearer（realm=STAFF）+ RBAC + 数据域，**契约禁止 `delete*`**，软删除走 `archive*`/`unarchive*`
- mock 层写操作**真落库**（重开能读回），状态机在 mock 层强制、非法迁移抛错

### 3.8 配置项（零硬编码）

| 项 | 位置 |
|---|---|
| `NEXT_PUBLIC_USE_MOCK` / `NEXT_PUBLIC_API_BASE` | `.env.local`（`.env.local.example` 入仓） |
| 主色皮肤色板 | 与 `packages/shared/src/design/tokens.ts` 的 `SKIN_HEX` 同名同色（mono/fresh/promo/blue）。**默认 mono**：运营端是密集表格，主色出现在每个链接/激活态上，彩色会跳（与 C 端默认 fresh 刻意区分） |
| 布局常量（rail 宽度等） | `lib/nav.ts` 顶部常量 |
| storage key | `lib/nav.ts` / stores 内常量，统一前缀 `shop-ops-` |
| 分页尺寸、防抖时长等 | `lib/constants.ts` |

---

## 4. 测试策略

| 测试 | 文件 | 断言 |
|---|---|---|
| token 约束 | `lib/design-tokens.test.ts` | `components/` 下无 hex/rgb/oklch；圆角只出现五档 |
| 导航结构 | `lib/nav.test.ts` | 同 group 叶子相邻；href 唯一；每个 L1 的 module 有权限码；**每条矩阵 P-x.y 至少被一个叶子覆盖** |
| 权限 | `lib/permissions.test.ts` | 11 角色 × 关键权限码矩阵；数据域裁剪函数 |
| 契约一致性 | `lib/api/contract.test.ts` | `mockApi` 与 `httpApi` 实现同一 `OpsApi`；无 `delete*` 方法名 |
| mock 落库 | `lib/mock/db/*.test.ts` | 写操作可读回；非法状态迁移抛错 |
| 工具 | `export-csv` / `import-csv` / `form-validate` | 随组件搬入，原测试一并搬 |

关键场景：① 切角色导航即变 ② `USE_MOCK` 翻转页面零改动 ③ 明暗 × 4 皮肤下 `/dev/ui` 无对比度回退 ④ 无权限页面出 `ReadOnlyNotice` 而非 404。

---

## 5. 风险与注意事项

1. **不与 C/B 端共用组件**：技术栈不同（React/Tailwind vs Vue/UnoCSS），共用的是 token 语义与契约口径。试图共用组件会两头受制。
2. **数据域是新增维度**，powerbank 只有租户一维。`scopeOf` 必须从第一天就在请求层生效，事后补是重构。
3. **前端裁剪 ≠ 鉴权**：矩阵 §2.3 明确「不靠前端隐藏」，后端未就绪期间 mock 层也要按 scope 过滤，避免形成错误预期。
4. **PRD 缺位**：矩阵 §十 列的 `PRD-平台端ops-web.md` 尚未编写。本脚手架不依赖它（只做地基），但**各业务域页面开工前必须先补 PRD**。
5. 中文字体走 CDN（powerbank 的已知代价）：内网部署会静默回退到系统字体。若 ai-shop 需内网部署，改自托管。
6. powerbank 的 `phase`/`ready` 双字段机制是为「60 个灰叶子灰得莫名其妙」而生的，ai-shop 起步阶段几乎全灰 —— 直接沿用，别简化成一个字段。

---

## 6. 实现任务

- [x] T1 工程初始化：`ops-web/` + 依赖 + tsconfig/next/vitest 配置 + 根 workspaces 与脚本
- [x] T2 地基搬运：`globals.css` token 三层 + `layout.tsx` + `providers` + `lib/{utils,notify,export-csv,import-csv,form-validate,phase}` + stores（改 key 前缀）
- [x] T3 UI 组件层：31 个 `components/ui/*` + 6 个 `components/layout/*` + `components/README.md` 分层规矩
- [x] T4 业务骨架：`lib/auth.ts`（11 角色）+ `lib/permissions.ts`（权限码 + 数据域）+ `lib/nav.ts`（18 个 L1，逐行对照矩阵 §六）
- [x] T5 请求层：`lib/api` 四件套 + `/ops/**` 契约首批（auth / dashboard / merchant / order）+ mock db 切片
- [x] T6 页面：`/login`（选角色）+ `/`（工作台）+ `/merchants`（样板页①）+ `/orders`（样板页②）+ `/dev/ui`
- [x] T7 测试：§4 全部用例，`npm run check` 全绿
- [x] T8 文档：`ops-web/README.md` + 回填 `architecture.md` 与需求矩阵 §九 M2 的结论

## 9. 契约导出给后端（2026-08-06）

`ops-web/scripts/gen-openapi.mjs` → `docs/api/openapi-ops.yaml`（**131 个端点 / 117 条路径 / 102 个类型定义**）。
运行：`cd ops-web && npm run gen:api`。

数据流：`lib/api/https/*.ts`（method + path）＋ `lib/api/contracts/*.ts`（方法名 + JSDoc → summary）
＋ `lib/types/index.ts`（→ ts-json-schema-generator → components.schemas）。

**生成器会因为契约与端点对不上而失败退出**，不是静默少生成几个 —— 生成一份「少了几个端点」的
规格比不生成更糟，后端会照着它写，联调时才发现缺接口。已实测：往契约里加一个没实现的方法，
`gen:api` 立刻报「契约声明了但 http 实现里没有的方法」。

同一判据也挂到了 `npm run check` 上（`lib/api/openapi-parity.test.ts`），这样提交前就会红，
而不是等人主动想起来跑生成器。

### 两个实现期的坑

1. **手写 YAML emitter 输出了非法 YAML**：数组里放对象时，首键必须跟在 `- ` 后面同一行。
   第一版写成 `- \n  key:`，脚本打印「✓ 成功」但文件根本解析不了 —— 这是最坏的一种失败。
   已加**自校验**：产物解析不回来就不写文件。
2. **正则把没有 JSDoc 的契约方法吞掉了**：「可选注释组 + 方法签名」的大正则会贪婪跨过无注释的方法
   （`getFastRefundRule` 就这么消失的）。改成逐行状态机。幸好一致性校验兜住了，否则产物会静默少一个端点。

## 8. 脚手架残留清理（2026-08-06）

提取自上游项目的东西，**留下的每一件都要能指到本项目需求矩阵里的一条**，指不到就删。已删：

| 删除项 | 理由 |
|---|---|
| `NavSection.portalFor` + `portalTitleOverride` + `lib/use-portal-title.ts` | 上游给外部代理商做的「专属门户」机制。ai-shop 的 11 个角色都是平台内部岗位，受限视角由**数据域**表达（矩阵 §2.3），不需要第二套菜单 |
| `components/ui/avatar.tsx`（+ `@radix-ui/react-avatar` 依赖） | 平台端是员工账号台账，矩阵 P-1.1 里没有展示头像的地方 |
| `components/ui/site-map.tsx` | 上游的点位撒点地图（脚手架阶段即未搬入） |
| dev 预览页里的上游业务样例文案 | 「仓位 / 站点」等改为本项目词汇 |

保留但尚未被业务页调用的组件，逐个在 `components/README.md` 里标注了对应的矩阵条目 ——
不是"先留着说不定有用"。

新增守卫测试 `lib/no-scaffold-leftover.test.ts`：上游业务名词、项目名（溯源文件白名单除外）、
已删的门户机制标识符，出现即红。⚠️ 判据要认业务不认字形 —— 第一版把「租户」当外来词，
直接把 `lib/types/common.ts` 判红了（`tenant_no=MAIN` 是矩阵 P-17.1.6 自己的预留）。

## 7. 实现记录（2026-08-05）

- 提取 31 个 `components/ui/*` + 6 个 `components/layout/*` + token 三层 + 请求层四件套，**未搬** `site-map.tsx`（充电宝点位地图）
- 业务层全部重写：11 角色 × 权限码 × 数据域、18 个 L1 导航（逐条对应矩阵 §六）、`/ops/**` 契约首批四域
- 交付页面：`/login`、`/`（看板）、`/merchants`（样板页①）、`/orders`（样板页②）、`/dev/ui`（dev-only 组件总览）
- 存储 key 统一前缀 `shop-ops-`；皮肤 mono/fresh/promo/blue，默认 mono
- 测试：`nav.test` / `permissions.test` / `contract.test` / `mock/db/merchant.test` / `i18n.test` / `design-tokens.test`（基线 0）
- 校验结果：`npm run check` = tsc 0 error + vitest **129 passed**；`npm run build` 静态导出 7 个路由通过
- 实机验证（浏览器）：登录 → 看板（KPI/趋势/漏斗）→ 商家审核状态机（审核中→已通过，抽屉按钮随之变为「授予认证标」）→ 订单列表 → 切 ANALYST 角色后 Rail 由 18 项收窄到 7 项且出「仅可查看」提示，控制台零报错

### 实现期发现并修掉的三个问题（都不是本方案的假设错误，是提取件的现实缺陷）

1. **recharts 2 的入场动画在 React 19 下停在第 0 帧** —— 折线只剩一个点、柱子高度为 0，看着像"没数据"。已在图表上关掉 `isAnimationActive`（看板不需要入场动画）。
2. **Badge 与表头会折行** —— 列宽挤时「待审核」被折成两行、表头行高翻倍。已给 `ui/badge.tsx` 与 `ui/table.tsx` 的 `th` 加 `whitespace-nowrap`（Table 本就有横向滚动兜底）。
3. **mock 里同步 `throw` 拿不到 rejected promise** —— 与真实后端「返回错误码」行为不一致，react-query 的 onError 不触发。会抛错的 mock 方法一律改成 `async`。

**未做（不属于脚手架，需各域 PRD 后另立 TDD）**：其余 14 个业务域的页面，导航中一律 `soon` 灰显。

---
确认记录：2026-08-05 用户确认（交付范围=地基+组件+4 样板页；i18n=保留骨架只填中文；默认皮肤=mono）
