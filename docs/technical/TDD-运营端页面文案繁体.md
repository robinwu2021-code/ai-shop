# TDD-运营端页面文案繁体

状态：已确认（2026-11-14 用户：台湾主场景，运营端简/繁/英三语）· 档位 1（i18n 呈现 + 新依赖；无端点、无库表、无权限码）
关联：2026-11-14 支付场景链路 review（UME166）发现 R3；「运营代建店主邮箱账号」草稿的 AC7 依赖本项（两份记录尚未入库）。
创建：2026-11-14 · 最后更新：2026-11-14

## 现状

- 框架层 `lib/i18n`（按钮、状态、导航）已有 `zh` / `zh-TW` / `en` 三套。
- 页面正文 `PageCopy` 只有 `zh` / `en`（`lib/use-copy.ts:17`），`useCopy` 在 `zh-TW` 下 `dict["zh-TW"]` 为空，回落 `dict.zh` —— **繁体界面整页简体**。22 个 `app/*/copy.ts` 共 2304 条。
- UME166 实测：切到「繁」后入驻管理列表、筛选、状态、新增商户表单全部简体。
- 独立 PAY 运营端已用 `opencc-js@1.4.2`（`cn → twp`）运行时转换，并对「登錄 / 賬號 / 賬戶」做台湾用语修正。

## 验收标准

- AC1：Given 语言为繁体，When 任一使用 `useCopy` 的页面渲染，Then 正文为台湾繁体（不再回落简体）。
- AC2：Given 某页 `PageCopy` 提供 `"zh-TW"` 局部覆盖，Then 覆盖条目优先，其余条目仍为转换结果。
- AC3：转换保持 `{name}` 占位符与 ASCII 原样，`fill()` 照常替换。
- AC4：台湾运营用语修正：「稽核→審核」「郵箱→信箱」「賬→帳」（帳號、帳戶、帳單）、「登錄→登入」。
- AC5：导航 `tNav` 与框架 `t()` 在 `zh-TW` 缺条目时用同一转换兜底，不再回落简体。
- AC6：简体、英文输出与改动前逐字一致。
- AC7：只引入 `opencc-js/cn2t` 子包；同一字符串转换结果缓存，切换语言不重复转换整表。

## 不做

- 不逐条手写 2304 条繁体译文；需要人工润色的个别条目走 AC2 覆盖。
- 不改后端返回的业务数据（主体名称、行业名等），不改 R7 等「台湾业务用语」类展示问题（另项处理）。
- 不改独立 PAY 运营端。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | `lib/use-copy.ts#useCopy`：`zh-TW` → `traditionalCopy(dict)` |
| AC2 | `PageCopy` 加可选 `"zh-TW"?: Partial<T>`，与转换结果合并 |
| AC3 | `lib/i18n/zh-tw.ts#toTraditional` 只转 CJK，占位符不受影响（opencc 对 ASCII 透传），单测锁定 |
| AC4 | `toTraditional` 内固定修正表 `TW_TERMS` |
| AC5 | `lib/i18n/nav-labels.ts#tNav`、`lib/i18n/index.ts#translate` 的 `zh-TW` 分支兜底 |
| AC6 | 只动 `zh-TW` 分支；`page-copy.test` 原三条断言不变 |
| AC7 | `import { Converter } from "opencc-js/cn2t"`；`Map` 缓存单串、`WeakMap` 缓存整表 |

**孤立项**：无。

## §2 方案

### 契约变更
- 端点 / 库表 / 权限码 / 配置：无。
- i18n：页面文案新增繁体呈现（运行时转换）；`PageCopy` 类型加可选 `"zh-TW"`。
- 依赖：`ops-web` 增 `opencc-js@1.4.2`（与 PAY 同版本），锁文件只增该包。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `ops-web/lib/i18n/zh-tw.ts` | `toTraditional(text)`、`traditionalCopy(dict, overrides?)`；修正表与缓存 |
| 新增 | `ops-web/lib/i18n/zh-tw.test.ts` | AC3、AC4、AC7（缓存同一引用） |
| 修改 | `ops-web/lib/use-copy.ts` | `PageCopy` 可选 `zh-TW`；`useCopy` 繁体分支 |
| 修改 | `ops-web/lib/i18n/nav-labels.ts` | `tNav` 繁体兜底 |
| 修改 | `ops-web/lib/i18n/index.ts` | `translate` 繁体兜底 |
| 修改 | `ops-web/lib/page-copy.test.ts` | 新增：每个 copy.ts 的 zh 经转换后不含常见简体专用字（AC1） |
| 修改 | `ops-web/package.json`、`package-lock.json` | 依赖 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 |
|---|---|---|
| AC1 | `page-copy.test.ts › %s：繁体呈现不残留简体专用字`（22 页） | ✅ |
| AC2 | `zh-tw.test.ts › 局部覆盖优先，其余仍为转换结果` | ✅ |
| AC3 | `zh-tw.test.ts › 占位符与 ASCII 原样` | ✅ |
| AC4 | `zh-tw.test.ts › 台湾运营用语修正`、`手写繁体译文同样做用语修正` | ✅ |
| AC5 | `zh-tw.test.ts › 繁体兜底`（tNav / translate） | ✅ |
| AC6 | 既有用例全量回归 | ✅ |
| AC7 | `zh-tw.test.ts › 整表转换按引用缓存` | ✅ |

运行（2026-11-14，`ops-web`）：先写测试，`zh-tw` 模块缺失时两文件均红；实现后 `npx vitest run` → `Test Files 139 passed (139) · Tests 1264 passed (1264)`；`npx tsc --noEmit -p .` 无输出、退出 0。

浏览器（本地 mock，`localhost:3115`）：繁体下入驻管理列表、筛选（含状态选项）、「新增商户」表单全部为台湾繁体（店主帳號、店主已註冊信箱、預設門店名稱、審核中、已駁回、全部類型）；切回简体逐字不变。

## §6 对账二 · 设计 → 实现

| 差异 | 说明 |
|---|---|
| 新增改动 `components/ui/status-badge.tsx`、`components/ui/filter-select.tsx` | 浏览器验证发现状态徽标与筛选选项来自各页 `StatusMap` 简体常量（21 个文件），不经 `useCopy`；在两个渲染件里统一转换，不逐页改 |
| `zh-tw.ts` 增 `twTerms` 与「型別→類型」 | twp 把「类型」译为程序术语；手写的 `messages/zh-TW.ts`、`nav-zh-TW.ts` 里「賬號」也需同一修正 |

### 偏差与遗留
- 体积：`opencc-js/cn2t` 未压缩约 1.1 MB，随主包加载（与 PAY 运营端相同做法）。如需优化，可改为仅繁体时动态加载。
- 英文界面下 `StatusMap` 文案仍是中文（既有问题，不属本 TDD），记为支付场景 review R25。
- `DetailFields`、表格列等直接渲染 lib 常量的个别位置若仍有简体，按页面实测补到 `PageCopy` 或渲染件。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-14 | 用户确认以台湾为主场景、三语；开始实现 |
| 2026-11-14 | 已实现（未提交）；ops-web vitest 全量、tsc 通过；本地 mock 浏览器验证繁体 |
