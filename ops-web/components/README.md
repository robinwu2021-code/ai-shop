# 组件分层与清单

三层，不要混。判断标准是**依赖方向**：下层不许知道上层的存在。

| 层 | 位置 | 判据 | 可以依赖 |
|---|---|---|---|
| **原语** | `components/ui/` | 无业务语义，换个行业照样能用。只认样式与 DOM | `lib/utils`、`lib/i18n`、`lib/form-validate`、`lib/notify`（都是无业务语义的工具） |
| **组合件** | `components/ui/` | 由原语拼成的通用交互单元（表格/抽屉/表单）。仍无业务语义，但有交互约定 | 原语 |
| **业务件** | `components/`（根） | 认得 ai-shop 的业务词：权限码、商家审核状态、订单状态、归档语义 | 原语 + 组合件 + `lib/types`、`lib/permissions` |

`ui/` 里放不下业务词。反过来，业务件不许被 `ui/` 引用 —— 这条一破，`ui/` 就不再可复用。

---

## 原语（primitives）

| 组件 | 文件 | 说明 |
|---|---|---|
| `Button` | `ui/button.tsx` | |
| `Input` / `Select` | `ui/input.tsx` | 裸控件。**筛选下拉请用组合件 `FilterSelect`** |
| `DateInput` | `ui/date-input.tsx` | |
| `Badge` | `ui/badge.tsx` | 导出 `BadgeTone` —— **全站色调联合的唯一真源** |
| `Card` / `CardHeader` / `CardContent` / `CardTitle` | `ui/card.tsx` | |
| `Table` / `THead` / `TBody` / `TR` / `TH` / `TD` | `ui/table.tsx` | 裸表格；列表页用 `DataTable` |
| `Tabs` | `ui/tabs.tsx` | 页内维度切换器（非 tab 导航）。形状（灰槽/全圆/字重）来自 `ui/segmented.ts` |
| `segmentedTrackClass` / `segmentedItemClass` | `ui/segmented.ts` | 分段控件（灰槽+全圆+白色药丸）的 className 拼装，供 `Tabs` 与 `TabHeader` 共用；两者场景不同（内容切换 vs URL 导航）不合并组件，只共享形状 |
| `Progress` | `ui/progress.tsx` | |
| `Notice` | `ui/notice.tsx` | 页内灰底提示条。权限降级用业务件 `ReadOnlyNotice` |
| `StatCard` / `StatRow` / `EmptyState` / `Skeleton` / `PageTitle` / `Pagination` / `PAGE_SIZES` | `ui/misc.tsx` | `StatRow` 是 KPI 卡片行；`Pagination` 传 `onSize` 才出「每页条数」 |
| `Tooltip` | `ui/tooltip.tsx` | |
| `Checkbox` / `CheckboxField` | `ui/checkbox.tsx` | 三态（含半选）。`DataTable` 的行选择用它 |
| `RadioGroup` / `RadioGroupItem` / `Radio` | `ui/radio-group.tsx` | 选项 ≤4 且需全部可见时用它，别用下拉 |
| `Switch` / `SwitchField` | `ui/switch.tsx` | **立即生效**的开关；待提交的布尔字段用 `Checkbox` |
| `Textarea` | `ui/textarea.tsx` | 多行输入，与 `Input` 同一套填充与圆角 |
| `Label` | `ui/label.tsx` | 可编辑控件的标签（`required` 出星号）。只读详情行是 `Field` |
| `Separator` | `ui/separator.tsx` | 语义分界线。**不要**拿它给卡片/工具栏描边 |
| `Popover` / `PopoverTrigger` / `PopoverContent` | `ui/popover.tsx` | 可交互轻浮层。Portal 定位，不会被 `overflow` 裁掉 |
| `DropdownMenu*` / `RowActions` | `ui/dropdown-menu.tsx` | 动作菜单；`RowActions` 是表格操作列的「更多」 |

## 组合件（composites）

| 组件 | 文件 | 说明 |
|---|---|---|
| `DataTable` | `ui/data-table.tsx` | 列表页表格：列配置 + 加载/空态 + 行选择/展开/排序/行样式 |
| `FormDrawer` | `ui/form-drawer.tsx` | 配置化编辑抽屉（`FieldDef[]` → 表单 + 校验 + 分区 + 联动） |
| `Drawer` / `DrawerSection` / `FieldGrid` / `Field` | `ui/drawer.tsx` | 右侧抽屉 + 分段 + 两列栅格 + **详情行**（`Field` 全站唯一一份，见下） |
| `ConfigCard` | `ui/config-card.tsx` | 配置卡片：标题 + 说明 + 内容 + 保存按钮 + 「上次修改」页脚。**配置页一律用它**，别再手拼页脚 |
| `Toolbar` | `ui/toolbar.tsx` | 搜索 + 筛选槽 + 导出/新增；选中时切批量操作条 |
| `TabHeader` | `ui/tab-header.tsx` | 页内 tab 条（含分期屏蔽） |
| `ConfirmDialog` / `useConfirm` | `ui/confirm-dialog.tsx` | 二次确认（支持 `requireText` 强确认） |
| `MultiSelect` | `ui/multi-select.tsx` | |
| `StatusBadge` / `StatusMap` / `statusOptions` | `ui/status-badge.tsx` | 「枚举 → 徽标」的渲染与类型。**映射表本身留在页面** |
| `FilterSelect` | `ui/filter-select.tsx` | 列表页筛选下拉；传 `StatusMap` 时选项自动派生。挂了 `toChip` → 选中态自动进筛选回显 |
| `FilterChip` / `chipsFrom` | `ui/filter-chip.ts` | 「生效中的筛选」chip 的登记契约。**新增筛选控件时必须挂 `toChip`**，否则它的选中态不会出现在回显里（`design-tokens.test.ts` 挡） |
| `Tree` | `ui/tree.tsx` | 层级树（类目树 / 权限树），可勾选（半选态复用 `Checkbox` 原语） |
| `Timeline` | `ui/timeline.tsx` | 审计时间线（时间 + 操作人 + 前后值 + 说明） |
| `Toaster` | `ui/toaster.tsx` | |

## 业务件（domain）

| 组件 | 文件 | 说明 |
|---|---|---|
| `MerchantStatusBadge` / `OrderStatusBadge` / `VerifiedBadge` | `status.tsx` | 域内固定枚举的徽标，文案走 i18n |
| `useFulfillTypeMap` / `useTrafficSourceMap` | `status.tsx` | 履约方式 / 流量来源的映射表（同时喂 `StatusBadge` 与 `FilterSelect`） |
| `useMerchantTierLabel` | `status.tsx` | 商家主体分层文案 |
| `ReadOnlyNotice` | `read-only-notice.tsx` | 权限降级提示，句式统一 |
| `ShowArchivedToggle` / `ArchiveActions` / `ArchivedAt` / `archivedRowClass` / `archiveConfirm` | `archive.tsx` | G1 软删除的页面侧统一件 |
| `Providers` | `providers.tsx` | React Query / 主题 / toast 的挂载点 |
| `AppShell` | `layout/app-shell.tsx` | 外壳：Rail + 顶栏 + 内容区 |
| `Rail` | `layout/rail.tsx` | L1 图标栏（18 个业务域） |
| `SecondaryNav` | `layout/secondary-nav.tsx` | L2 分组 + L3 子功能（含待建灰显与分期徽章） |
| `PhaseGuard` | `layout/phase-guard.tsx` | 分期门禁：直达未开放功能时的兜底页 |
| `ThemeSwitcher` | `layout/theme-switcher.tsx` | 顶栏皮肤切换（五套） |
| `LangSwitcher` | `layout/lang-switcher.tsx` | 顶栏**中 / EN** 语言切换 |

### `components/status.tsx` 的归位

它是**业务件**，不是原语。判据：它 `import type { MerchantStatus, OrderStatus } from "@/lib/types"`，
认得商家审核与订单状态机 —— 这是业务知识。它位于 `components/` 根是对的，不要挪进 `ui/`。
色调联合只有 `BadgeTone` 一份定义（`ui/badge.tsx`），不要在这里再写一遍字面量联合。

---

## 皮肤与语言

**皮肤**（`lib/stores/theme.ts` 的 `THEMES`，色值在 `app/globals.css` 的 `[data-theme=…]`）：

| key | 名称 | 换什么 |
|---|---|---|
| `mono` | 黑白灰 | 只换主色（默认皮肤） |
| `business` | 商务蓝 | **整套**：中性阶偏石板蓝 + 画布压深一档 + 藏蓝主色 |
| `fresh` / `promo` / `blue` | 生鲜绿 / 促销橙 / 时尚蓝 | 只换主色 |

**明暗**：顶栏皮肤面板里的「浅色 / 深色」，localStorage 持久化，首帧脚本先应用（否则会闪一下白）。
**刻意不跟随系统**：这页常常一开一整天，系统傍晚自动切暗色会很突兀，何况满屏是要对着念的数字。

- 只有 `business` 可以动中性阶与背景，因为它是**运营端专有**的；其余四套要与 C 端
  `packages/shared` 的 SKINS 同名同色，那边换肤只换主色。
- **`business` 不能下发给 C 端**（C 端没有这套）：`C_END_THEMES` 已经把它排除，
  类型与 mock 校验两处都挡，`system.test.ts` 锁这条。
- 语义色（成功/警告/危险/信息）**任何皮肤下都不变** —— 状态含义不能随皮肤漂移。
- **主按钮的文字色不是一律白的**：白字压在亮色主色上过不了 AA（生鲜绿实测 2.66:1）。
  每套皮肤各自选白字或墨字，取能过 4.5 的那个 —— 由 `lib/skin-contrast.test.ts` 锁死，改 hex 就红。
- **压在浅底上的文字一律用 `--*-ink` 档**，不要直接拿实心色当文字色：
  `--success` 落在浅绿底上只有 ~2.8:1，`--primary` 落在主色浅底上（时尚蓝）实测 3.18:1，
  都过不了 AA。主色那一档叫 `--primary-ink`，明暗两态公式相反（浅色往黑压、暗色往白提）。

**验对比度**：`/dev/ui` 的「全量对比度」按钮跑 5 皮肤 × 明暗共 10 组，
直接在 DOM 上量（不依赖各探针的 React 状态），跑完自动还原你原来的皮肤。
改配色后点一次，比肉眼扫十遍可靠。

**语言**：中 / EN，顶栏 `LangSwitcher` 切换，localStorage 持久化。
覆盖到**导航与框架**（菜单、面包屑、分页、表格、状态枚举、表单校验）；
**页面正文仍是中文硬编码**（表头、说明条、空态文案）—— 这是 §九 M7「运营界面仅中文」的遗留。
`lib/i18n/i18n.test.ts` 锁 key 齐平 + 英文里不许残留汉字，`nav.test.ts` 锁导航标签译名全覆盖。

## 组件的去留判据（2026-08-06 清理）

组件库提取自另一个项目，所以定过一条判据：**留下的每个组件都要能指到本项目需求矩阵里的一条**，
指不到的就删，不管它多"通用"。

已删：
- `ui/site-map.tsx` —— 充电宝的点位撒点地图。ai-shop 的社区/自提点地图属于另一套需求，届时另做
- `ui/avatar.tsx` —— 平台端是员工账号台账，矩阵 P-1.1 里没有任何要展示头像的地方
- `lib/use-portal-title.ts` + `NavSection.portalFor` —— 那是充电宝给外部代理商做的"专属门户"机制。
  ai-shop 的 11 个角色**都是平台内部岗位**，受限视角由**数据域**表达（矩阵 §2.3），不是由另一套菜单表达

保留但尚未被业务页调用的，各自对应矩阵里已排期的需求（不是"先留着说不定有用"）：

| 组件 | 对应需求 |
|---|---|
| `Tree` | P-1.1.2 角色与 RBAC（权限树勾选） |
| `Timeline` | P-1.1.4 操作审计日志 |
| `FormDrawer` | P-3.2.1 商品维护、P-2.2.1 自提点建档 |
| `MultiSelect` / `RadioGroup` / `DateInput` | 同上，配置类表单 |
| `DropdownMenu` / `RowActions` | 本文件的「行内动作 ≤2 个，其余进 RowActions」约定 |
| `Popover` | P-16.2 风控规则的行内速查 |
| `lib/import-csv.ts` | P-3.2.4 批量导入/改价 |

## 页面级 hooks（`lib/`，与组件同属"别再复制粘贴"这条线）

| hook | 文件 | 挡住什么 |
|---|---|---|
| `usePageTab(TABS, onSwitch)` | `lib/use-page-tab.ts` | tab 与 URL 同步的 8 行样板。手写会漏 `setPage(1)` → 切了 tab 还停在第 3 页，列表空白像没数据 |
| `usePaging()` | `lib/use-paging.ts` | 页码 + 每页条数。`setSize` 内部一定复位到第 1 页 |
| `useEditableConfig(data, toForm)` | `lib/use-editable-config.ts` | 配置表单的 `form ?? 派生` 模式。`patch/set` **内部就是函数式更新**，写不出 stale closure |
| `useCopy(COPY)` | `lib/use-copy.ts` | 页面正文的中英对照。文案表按页就近放 `app/xxx/copy.ts`，不塞进全站 catalog |
| `useCan()` | `lib/use-can.ts` | 权限判定（`can` / `canModule` / `scopeOf` 的 React 侧入口） |

这四个都有守卫盯着（`lib/design-tokens.test.ts`），绕开就会红。

## 页面骨架（22 个页面必须长一个样，`design-tokens.test.ts` / `nav.test.ts` 盯着）

```
TabHeader（页头，单 tab 也走它，传 desc）
  ReadOnlyNotice（无权限时）
  Notice（这一屏的前提/风险，用 tone 分档）
  Toolbar（搜索 + 筛选槽；筛选回显 chip 自动出）
  DataTable（列表；空态要写清「为什么空、下一步做什么」）
  Pagination（一页一个，绑 activeList = 当前 tab 的查询）
  Drawer / ConfigCard（详情或配置）
```

几条已被守卫锁住的硬约定：

- **页头只有 TabHeader 一种**。`PageTitle` 只留给没有 L3 导航的工作台。
- **nav 里已解锁的 `?tab=` 叶子，页面必须真的认这个 tab** —— 否则点了只有面包屑变、内容不变。
- **一页一个分页器**，`total` 绑 `activeList`（当前 tab 的那个 query）。
  多 tab 页面统一用这个名字：`current` 通常已经被"选中的那一行"占用了。
- **空态文案 ≥20 字**，要说清为什么空、下一步做什么。运营看到空表第一反应是"系统坏了"。
- 金额一律 `money()`、时间一律 `fmtTime()`、数字列一律传 `numeric`。
- 行内操作 ≤2 个，其余进 `RowActions`。

## 用法约定（各业务域铺开时照这个来）

**props 命名沿用 `DataTable` / `FormDrawer` 的既有习惯**（样板页见 `app/merchants` 与 `app/orders`）：
- 数据入参：`rows` / `columns` / `items` / `options`
- 空态文案：`empty`，且要写清「**为什么**空、下一步做什么」，不要只写「暂无数据」
- 受控值：`value` + `onChange`，回调直接给值不给 event
- 样式逃生口：`className`（`cn` 走 tailwind-merge，可覆盖内置类）

**颜色一律用 token**（`--*-tint` / `--*-ink` / 语义色），不要写死 hex。见 `app/globals.css` 顶部注释，
由 `lib/design-tokens.test.ts` 拦截（组件层与页面层基线都是 0）。

**焦点环只写 `focus-ring` 一个类**（`app/globals.css` 的 `@utility`）。
不要再手写 `focus-visible:ring-2 focus-visible:ring-ring …` 那一串 ——
此前它在 14 个组件里各抄一遍，结果最常用的 Button 漏了 `ring-offset` 两项，
`/dev/ui` 规范体检一次报出 35 处。

**边界靠线，不靠阴影**。卡片/表格容器带一条 `--card-border` hairline；阴影只负责"浮起来"。
画布（`--background`）与卡片（`--card`）**必须差一档** —— 两者都是纯白时对比度 1.00，
低亮度屏幕上卡片边界直接消失。

### `Field` 只此一份

- `ui/drawer.tsx` 的 `Field` 是唯一的**详情行**，默认 `mb-4`；
  放进 grid / flex 由容器给间距时传 `className="mb-0"`。
- `ui/form-drawer.tsx` 的 `FieldRow` **不是重复定义**，是表单行（必填星号 / 字数计数 /
  错误态 / 控件分发）。两者只有外框间距长得像，合并会把表单关注点塞进只读展示件 —— 保持分开。

### 四种浮层怎么选

选错就会出现第二份重复实现，这条按**内容性质**判，不按「大小」判：

| 内容 | 用 | 依据 |
|---|---|---|
| 一句纯说明，hover 即出，不可点 | `Tooltip` | 无焦点、无交互 |
| 一列**动作**（编辑/停用/归档） | `DropdownMenu` / `RowActions` | 方向键 + typeahead 由 Radix 保证 |
| 任意可交互内容（筛选面板、迷你表单、二维码） | `Popover` | 顺手看一眼 / 改一下，**不遮挡列表上下文** |
| 成体系的一件事，有标题与底部动作条 | `Drawer` / `ConfirmDialog` | 进去做一件事 |

一句话判据：**Popover 是「顺手改一下」，Drawer 是「进去做一件事」。**

### 表格列的默认行为

- **单元格默认不换行**（`whitespace-nowrap`）：密集台账里列一窄就逐字换行，行高翻几倍。
  需要换行的列显式传 `className="whitespace-normal"`，长文案用 `line-clamp-1` + 抽屉看全文。
- 表格有 `min-w-[56rem]`：窄视口下**横向滚动**而不是压缩列宽。
- 数字列传 `numeric`（右对齐 + 等宽数字），不要手写 `text-end tabular-nums`。
- 表头吸顶（`sticky`），底色不透明。

### 状态映射表放哪

映射表是**业务语义**（哪个状态叫什么、该是什么色），留在使用它的页面里：

```tsx
const SETTLE_STATUS: StatusMap<Settlement["status"]> = {
  PENDING: { label: "待分账", tone: "warning" },
  SPLIT:   { label: "已分账", tone: "success" },
};

<StatusBadge map={SETTLE_STATUS} value={s.status} />
<FilterSelect value={f} onChange={setF} allLabel="全部状态" options={SETTLE_STATUS} />
```

**全站通用**的枚举（商家审核状态、订单状态）例外：它们放 `components/status.tsx`，
否则同一个订单状态在订单页、售后页、结算页会配出三种颜色。判据是「这个枚举会不会出现在第二个页面」。

⚠️ 映射表的**键序即筛选下拉的选项顺序**。改键序 = 改 UI，别随手排序。
