# TDD-餐饮POS点餐收银界面

状态：草稿
关联需求：docs/requirements/餐饮系统-需求矩阵.md §二 C / D / F / G / H / K 组，§三 对应验收行
关联设计：docs/technical/design/餐饮系统-UI组件与主题方案.md（§九 定稿）
创建：2026-10-22 · 最后更新：2026-10-22

> 档位 1：本 TDD 只覆盖 b-app 的**收银平板点餐页界面**迁入，动到的契约是页面路由（`pages.json`）、i18n 词条、餐饮设计令牌（`packages/ui` 内 `pos-v2` 作用域的配置）。点餐、桌台、结账的业务端点大多还没实现（见 §1），本轮界面先接 b-app mock 层；端点落地另走各自的 TDD。

## §0 对账一 · 需求 → 设计

视觉与交互真源：设计规范「Dining POS」（L1 布局 + B 端色块），可交互原型 `pos-workbench.html`（方案 1 + L1）。下表的 AC 是把矩阵里的「最低验收结果」落到这一页界面上的可观测断言。

| AC | 需求原文（一句话） | 落点（模块 / 组件） |
|---|---|---|
| AC1 · FNB-D02/D05 | 收银代客点餐；同 SKU 不同做法分行 | `DiningMenuItem` 单击加一份；`DiningSpecDialog` 选规格后按「SKU + 做法 + 备注」合并或分行；`useDiningCart` |
| AC2 · FNB-D04 | 金额变化必须重新确认，不得静默替换 | `DiningOrderDock` 只显示服务端报价；报价版本变化时弹 `sh-confirm` 让收银员确认 |
| AC3 · FNB-D03 | 提交只清理本次草稿 | 「送厨」只把「待下单」行转为「已下单」，已下单行与其他终端草稿不动 |
| AC4 · FNB-G01/G02 | 未提交删菜；已送厨退菜要申请 / 审批 | 待下单行：删除（`sh-confirm`）；已下单行：退菜 → `DiningAuthPad` 授权 → 选原因 |
| AC5 · FNB-D06 | 赠送与退款免单区分 | 赠送单独标「赠」并在金额区单列「赠送」，不改原价快照；需授权 |
| AC6 · FNB-G08 | 敏感操作有原因与审计 | 赠送、退菜、整单折扣统一经 `DiningAuthPad`，提交时携带授权人与原因 |
| AC7 · FNB-C05 | 开台、人数；支持无桌快餐 | 桌台总览 `DiningTableTile` 开台 + 人数；外带 / 外卖订单无桌台 |
| AC8 · FNB-C06 | 并桌、转台保留原交易资金归属 | 桌台操作：换桌、并台、转菜；并台后被并桌显示「并入 A08」 |
| AC9 · FNB-C07 | 合账拆账关系可恢复留痕 | 订单面板：合单、拆单；行上标「合自 / 转自 / 来自」 |
| AC10 · FNB-C08 | 清台不删除未收应收 | 有未结账菜品时「清台」被拒并说明原因 |
| AC11 · FNB-F01 | 预结不代表已付 | 「打印预结单」只出票，不改变订单状态 |
| AC12 · FNB-F02/F04 | 只允许真实受权收款；工具来源可追踪 | `DiningPaymentTool` + `DiningCashPad`；实收不足不能确认；收款结果以支付回执为准 |
| AC13 · FNB-F06/F07 | 优惠抹零分项；服务费分项 | 金额区分项：小计、服务费（仅堂食）、打包费、赠送、会员、券、抹零、应收 |
| AC14 · FNB-H01/H02/H05 | 路由分单；失败回执与人工补打 | 送厨按工位分单；打印失败不阻塞送厨，系统栏打印入口变警告，打印面板可重打（补打标识） |
| AC15 · FNB-K03 | 断网可点餐制作与获准现金收款 | 离线：送厨进待同步队列；支付方式中扫码类标「离线不可用」，现金和刷卡可用 |
| AC16 · 设计规范 | L1 布局；宽度 < 1000 时折叠；RTL 镜像 | `DiningPosShell` 网格；窄屏分类横排 + 结单抽屉；阿拉伯语下左右整体镜像 |

**孤立项：**
- 没落点的 AC：无。FNB-F03（整桌跨订单一次结账）、F05（按人按菜拆账）本轮界面只做到「合单后一次结账」「拆出新单单独结」，按人 / 按菜分摊等核心支持后再补。
- 挂不上 AC 的设计：会员识别与优惠券（属会员资产域，见 `TDD-活动卡券储值-模块化系统设计.md`），本页只调用、不新增能力；布局可切换（L2–L6）属设计规范的门店设置，不对应矩阵条目。

## §1 现状与影响面

- **相关现有模块：**
  - `packages/ui/src/components/sh-*`：页面外壳、弹层、确认、步进器、选项、键值行、统计、空态等，全部复用。
  - `packages/ui/src/styles/base.css`：`.sh-btn` `.sh-chip` `.sh-card` `.sh-num` `.sh-hit` 及 `--sh-*` 令牌，按原义使用。
  - `b-app/src/api/mock.ts`、`b-app/src/api/mocks/`：本轮界面的数据来源。
  - `prototypes/food-cashier-tablet.html`：既有餐饮原型（`pos-v2` 作用域），本页取代其「点餐收银」屏。
- **可直接复用：** `sh-sheet`（需加平板右侧滑出形态，见 §2）、`sh-confirm`、`sh-dialog`、`sh-stepper`、`sh-option`、`sh-kv`、`sh-stat`、`sh-empty`、`sh-check`、`sh-icon`、`biz-store-tag`。
- **会被改到的：** `packages/ui` 的 `sh-sheet`（新增 `side` 形态，移动端默认行为不变）；`packages/shared/src/design/icons.ts`（补 POS 图标）；`b-app/src/pages.json`；三份 locale。
- **端点现状：** 后端尚无餐饮点餐、桌台、结账、打印的 `/biz` 端点（仅有 `/internal/product/dish-offers/batch` 与探针）。CAT01 文档记录菜品业务 API016/112/113 未实现；结账走 `TDD-统一试算引擎与餐饮结算.md`。本页所有写操作先落 mock，端点就绪后替换。
- **明确不受影响的：** C 端全部页面；B 端现有 52 个页面的外观（`pos-v2` 令牌不进入全局主题）；后端。

## §2 方案

### 契约变更
- 端点：无（本轮）。依赖清单见下表「端点依赖」，由各域 TDD 交付。
- 库表 / 字段 / 迁移号：无。
- 权限码：无新增；沿用矩阵的「收银：操 / 申请」角色，授权走主管授权码（端点依赖 E7）。
- i18n 词条：新增 `pos.*` 命名空间，zh-CN / en / ar 三语，约 140 条（系统栏 6、桌台操作 9、订单面板 25、支付 20、打印 15、网络 10、设置 20、提示条与错误约 35）。
- 配置项：`packages/ui` 新增 `pos-v2` 作用域令牌（`pos-ctl` `pos-ctl-s` `pos-ctl-l` `pos-sys-h` `pos-rail-w` `pos-order-w` `pos-tile-w` `pos-tile-h` `pos-gap` 与 `pos-success-text` `pos-warning-text` `pos-danger-text` `pos-info-tint` `pos-info-text`）；POS 皮肤松绿 / 靛蓝 / 暖橙 / 绛红 / 石墨写入 `tokens.ts` 的 POS 段，由 `gen:skins` 生成，不改现有 9 套皮肤。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `b-app/src/pages/pos/index.vue` | 点餐收银页，根元素 `sh-scaffold`，内含 `DiningPosShell` |
| 新增 | `b-app/src/components/dining/dining-pos-shell.vue` | 系统栏 + 三栏网格；布局由 `useDiningLayout` 决定；窄屏折叠 |
| 新增 | `b-app/src/components/dining/dining-entry-button.vue` | 系统栏入口（计数 / 状态点） |
| 新增 | `b-app/src/components/dining/dining-resource-rail.vue` | 分类区，竖排 / 横排 |
| 新增 | `b-app/src/components/dining/dining-menu-item.vue` | 菜品卡（整卡一个按钮，长按详情） |
| 新增 | `b-app/src/components/dining/dining-spec-dialog.vue` | 规格 / 套餐 / 备注，基于 `sh-dialog` + `sh-option` |
| 新增 | `b-app/src/components/dining/dining-order-dock.vue` | 桌台订单条 + 明细 + 金额与操作 |
| 新增 | `b-app/src/components/dining/dining-table-tile.vue` | 桌台卡 |
| 新增 | `b-app/src/components/dining/dining-payment-tool.vue` | 支付方式（含离线不可用说明） |
| 新增 | `b-app/src/components/dining/dining-cash-pad.vue` | 现金键盘与找零 |
| 新增 | `b-app/src/components/dining/dining-auth-pad.vue` | 主管授权码 |
| 新增 | `b-app/src/components/dining/dining-status-label.vue` | 状态标签（`.sh-chip` + 圆点） |
| 新增 | `b-app/src/components/dining/sheets/*.vue` | 桌台、订单、会员、打印、网络、设置六个面板，全部基于 `sh-sheet side` |
| 新增 | `b-app/src/composables/dining/use-dining-cart.ts` | 订单行合并规则、待下单 / 已下单、选择模式（转菜、拆单） |
| 新增 | `b-app/src/composables/dining/use-dining-quote.ts` | 金额分项（以服务端报价为准；mock 期本地计算并标注） |
| 新增 | `b-app/src/composables/dining/use-dining-layout.ts` | L1–L6 布局、宽度断点、RTL 镜像 |
| 新增 | `b-app/src/api/mocks/dining.ts` | 桌台、订单、打印机、网络的 mock |
| 修改 | `packages/ui/src/components/sh-sheet.vue` | 新增 `side` 属性：平板右侧滑出；默认仍为底部弹出 |
| 修改 | `packages/shared/src/design/tokens.ts` | POS 段令牌与 5 套 POS 皮肤 |
| 修改 | `packages/shared/src/design/icons.ts` | 补 chef-hat、printer、merge、split 等图标 |
| 修改 | `b-app/src/pages.json` | 注册 `pages/pos/index` |
| 修改 | `b-app/src/i18n/locale/{zh-CN,en,ar}.ts` | `pos.*` 词条 |
| 修改 | `docs/technical/design/ui-catalog.json` | 跑 `gen-ui-catalog.py` 生成 |

### 迁移约束（从原型到 b-app）
- **不手搓库里已有的件**（`check-handrolled-ui`）：原型里自绘的侧滑面板、确认框、步进器，迁移时分别改用 `sh-sheet side`、`sh-confirm`、`sh-stepper`；系统原生 `uni.showModal` 一律不用。
- **RTL**：原型用 `left/right` 写定位；迁移改为逻辑属性（`inset-inline-start`、`padding-inline` 等），网格模板在 `dir="rtl"` 下整体镜像，金额加 `.sh-num` 强制 LTR（`known-rtl-physical.txt` 只准变短）。
- **单位**：b-app 以 rpx 计；POS 平板走 `pos-v2` 作用域的 px 令牌（平板 >600px 时页面不再按 375pt 收窄），作用域外不生效。
- **图标**：Lucide 只用于原型；落地用 `sh-icon`，颜色跟随主题。
- **字体**：Inter + Noto Sans SC 由 Google Fonts 提供；小程序端不加载远程字体，回退苹方 / 鸿蒙黑体。

### 端点依赖（由各域 TDD 交付，本页先接 mock）
| # | 能力 | 对应需求 |
|---|---|---|
| E1 | 桌台列表与状态、开台、改人数、换桌、并台、清台 | FNB-C05–C08 |
| E2 | 菜单读取（分类、菜品、规格、库存 / 沽清） | FNB-B 组、CAT01 |
| E3 | 购物车草稿与送厨提交（批次、做法分行） | FNB-D02–D05 |
| E4 | 报价（服务费、打包费、会员、券、抹零） | FNB-F06/F07、统一试算引擎 |
| E5 | 支付下单与回执（现金确认、扫码、刷卡、储值） | FNB-F02/F04 |
| E6 | 打印路由、任务与回执、补打 | FNB-H01–H05 |
| E7 | 主管授权与敏感操作审计 | FNB-G08 |
| E8 | 离线队列与恢复同步 | FNB-K03/K04 |

### 关键接口
```ts
// use-dining-cart.ts
type LineStatus = 'pending' | 'sent' | 'void'
interface DiningLine { lineId: string; skuId: string; name: string; specText: string; note: string;
  unitMinor: number; qty: number; status: LineStatus; gift: boolean; rush: boolean;
  origin?: { kind: 'merge' | 'move' | 'combine'; from: string } }
function addItem(sku: DiningSku, spec: SpecSelection, qty: number, note: string): DiningLine
function sendPending(): Promise<{ sent: DiningLine[]; printFailures: string[]; queuedOffline: boolean }>

// dining-order-dock.vue
props: { order: DiningOrder; quote: DiningQuote; selecting?: 'move' | 'split' | null }
emits: ['send', 'pay', 'hold', 'line-action', 'table-op']

// use-dining-layout.ts
type CatPos = 'top' | 'left' | 'right'; type OrderSide = 'left' | 'right'
function gridFor(cat: CatPos, order: OrderSide, width: number, rtl: boolean): { columns: string; rows: string; areas: string }
```

## §5 对账三 · 实现 → 需求（测试）

实现后填写「跑过」「消融验证」两列。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `use-dining-cart.test.ts › same sku different spec makes two lines` | | |
| AC2 | `dining-order-dock.test.ts › quote version change asks to confirm` | | |
| AC3 | `use-dining-cart.test.ts › sendPending only moves pending lines` | | |
| AC4 | `use-dining-cart.test.ts › void requires auth and reason` | | |
| AC5 | `use-dining-quote.test.ts › gift listed separately and keeps unit price` | | |
| AC6 | `dining-auth-pad.test.ts › emits approver with reason` | | |
| AC7 | `dining-table-ops.test.ts › open table sets guests; takeaway has no table` | | |
| AC8 | `dining-table-ops.test.ts › merge marks source table merged into target` | | |
| AC9 | `use-dining-cart.test.ts › split creates new order and tags origin` | | |
| AC10 | `dining-table-ops.test.ts › clear table rejected with unpaid lines` | | |
| AC11 | `dining-print.test.ts › prebill does not change order status` | | |
| AC12 | `dining-cash-pad.test.ts › confirm disabled until received covers due` | | |
| AC13 | `use-dining-quote.test.ts › service fee only for dine-in; rounding last` | | |
| AC14 | `dining-print.test.ts › routes by station; failure keeps lines sent and flags printer` | | |
| AC15 | `dining-payment-tool.test.ts › offline disables scan methods with reason` | | |
| AC16 | `use-dining-layout.test.ts › L1 grid; narrow collapses; rtl mirrors` | | |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-22 | 草稿：设计定稿 L1 + B 端色块，待评审组件 API 与端点依赖排期 |
