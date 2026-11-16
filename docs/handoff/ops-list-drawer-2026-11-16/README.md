# 运营端列表与抽屉规范收口 · 交接

> 状态：已合入 `main` 并推送；**2026-11-16 12:47 已发布 UME166**（发布 `list-drawer-20261116-1efcbe741`，源码 `1efcbe741`），nearshop 与 twpay 两域 `/ops-shop/` 公网核对通过，登录后浏览器验收未做。发布记录见 [release-166.json](release-166.json)。
> 范围：仅 `ops-web` 与文档，档位 0（未改接口、库表、权限码、i18n 词典；页面 `copy.ts` 词条有增删）。

## 一、接手结论

0. 已于 2026-11-16 发布到 UME166（两个域名 `/ops-shop/`），只换前端。
1. 运营端「表格 + 操作列 + 右侧抽屉」有了成文规范，门店与商户两组页面已按它收口。
2. 线上运行的分支 `codex/ops-customers-simplify`（比旧 `main` 多 41 个提交，含「客户管理」菜单）已合入 `main`。今后部署应从 `main` 出，该分支可以退役。
3. 门店抽屉现在是一个 720px 抽屉、五个页签：**基本信息 / 经营状况 / 订单 / 经营审核 / 覆盖与取货**。订单页签是新功能：按门店列出订单，可按状态筛、可翻页。
4. 写了独立的运营端功能矩阵（账户 / 商户 / 门店 / 渠道，88 项），并与现状做了对照。
5. 还没做：登录后在线上验收门店抽屉，详见 §五。

## 二、提交清单（`main`）

| 提交 | 内容 |
|---|---|
| `325a3a917` | 门店、商户页面按规范收口：行内动作 ≤2、危险动作进「更多」、动作进底栏、抽屉宽度四档；套餐抽屉改为详情 → 授予 / 额度三态；支付服务表单不再叠抽屉；清理说明性文字 |
| `48cc0490b` | 重跑生成器，产物与源码对齐（仅生成物） |
| `25a3620f1` | 合并 `codex/ops-customers-simplify`，解决冲突；门店抽屉加订单页签 |
| `3a53f7cab` | 需求文档：运营端账户 / 商户 / 门店 / 渠道功能矩阵 |
| `f1988ec07` | 门店抽屉拆开「基本信息」与「经营状况」 |

`ecdecea02`（run-local skill）是会话开始前本地已有、随这批一起推上去的提交，不属于本次工作。

## 三、交付物

### 3.1 规范与需求文档

| 文档 | 说明 |
|---|---|
| [规范-运营端-列表与抽屉](../../technical/design/规范-运营端-列表与抽屉.md) | 页面骨架、操作列、抽屉四种用途、宽度四档（默认 / 560 / 720 / 900）、底栏与提交、关闭、何时不用抽屉、文字只留标题 / placeholder / 风险提示 / 空态、评审清单、存量差距 |
| [运营端-账户商户门店渠道-功能矩阵](../../requirements/运营端-账户商户门店渠道-功能矩阵.md) | §一–§六 独立整理（不参考现状）；§七 与现状对照：✅ 23 / 🟡 33 / ❌ 32，含补齐顺序 |
| [`ops-web/components/README.md`](../../../ops-web/components/README.md) | 登记了下面的组件变化 |

### 3.2 公共组件变化（影响门店、商户以外的页面）

| 组件 | 变化 | 影响 |
|---|---|---|
| `components/archive.tsx` `ArchiveActions` | 新增 `view` 插槽（查看入口常显）；归档 / 恢复挪进 `RowActions`「更多」菜单 | 社区、营销页的归档按钮也变成「更多」里的一项。修掉一个缺陷：此前无写权限或已归档的行连「查看」都没有 |
| `components/ui/drawer.tsx` `DrawerFooter` | 抽屉里的子块可把按钮放进外层抽屉底栏；占用时外层 `footer` 让位 | 新增，无存量影响 |
| `components/ui/form-drawer.tsx` | `inDrawer` / `inDrawerActive`：在已打开的抽屉里原地展开，按钮进外层底栏；页签切走时保留输入 | 新增，默认行为不变 |
| `components/ui/data-table.tsx` | `compact`：去掉 56rem 宽度下限，给抽屉里的窄表用 | 新增，默认行为不变 |
| `lib/api/mocks/order.ts` | mock `listOrders` 支持 `storeNo`（无门店号的种子单按门店所属主体匹配） | 仅 mock |

### 3.3 门店抽屉（`ops-web/app/merchants/stores-tab.tsx`）

同一个组件出现在两处：商户中心 → 门店档案（`/merchants?tab=stores`），以及客户管理 → 门店（`/customers?tab=stores`，后者通过 `detailActions` 挂代购、会员入口）。

| 页签 | 内容 | 请求时机 |
|---|---|---|
| 基本信息 | 所属商户、营业时间、地址、公告、配送四项；`detailActions` | 列表行数据，不额外请求 |
| 经营状况 | 经营状态、经营模式、收款号；今日 / 本月订单与 GMV、自带客流占比；待处理四项；「看这家店的商品」 | 切到该页签才取 `getStoreStats` |
| 订单 | 本店订单，状态筛选、分页；「看这家店的订单」跳订单管理 | 切到该页签才取 `listOrders({ storeNo })` |
| 经营审核 | 分支原有的 `OperationalReview` | 原样 |
| 覆盖与取货 | 覆盖社区、排除项、实际可达、自提点、扫码数、评分 | 切到该页签才取 `getStore` |

「解除下线」在底栏，只对平台强制下线且有 `merchant:merchant:ban` 的人出现。订单行不点开详情（避免叠抽屉），处理单笔订单走订单管理。

## 四、合并说明（`25a3620f1`）

| 文件 | 处理 |
|---|---|
| `stores-tab.tsx` | 分支原设计是「主抽屉 + 四个二级抽屉」叠开，违反规范「不叠抽屉」。**改为一个抽屉多个页签**，保留分支的经营审核与 `detailActions` |
| `apply-tab.tsx` | 保留 `main` 的底栏按钮（驳回 / 取消 / 通过），其余用分支结构；分支删掉了详情里的「驳回原因」提示，按分支保留删除 |
| `on-behalf-tab.tsx`、`plans-tab.tsx`、`copy.ts` | 用分支结构，保留 `main` 对说明性文字的清理 |
| 生成文档 | 取分支版本后重跑生成器 |

分支新增依赖 `opencc-js@1.4.2`（繁体转换）。合并后的工作目录需要 `npm install` 才能编译。

## 五、待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | ~~部署~~ 已完成 | 2026-11-16 发布 `list-drawer-20261116-1efcbe741`，只换前端静态页；回退命令见 [release-166.json](release-166.json) |
| 2 | **登录后在线上验收门店抽屉五个页签** | 公网只核对了页面与资源一致、接口要求登录；登录后的交互（订单页签、经营状况、商户「更多」菜单）未在线上看过 |
| 3 | 共享目录 `/Users/neargo2026/work/ai/ai-shop` 切到新 `main` | 仍停在旧提交 `48cc0490b` 的 `feat/ops-list-drawer-spec` 分支；本地 mock 服务（3105）跑的是旧代码。切换会带进合并的大批文件变化，需与同目录的其他会话协调 |
| 4 | 装 pre-push 钩子 | 这个目录未设 `core.hooksPath`，本次推送都没经过 pre-push。执行 `git config core.hooksPath .githooks` |
| 5 | 清理分支 | 远端 `feat/ops-list-drawer-spec` 已无用；`codex/ops-customers-simplify` 已完全并入 `main` |
| 6 | 功能矩阵待决项 D1–D7 | D1「渠道」范围（当前按收款 + 销售渠道写）会改变矩阵规模 |
| 7 | 补齐 P0 缺口 | 矩阵 §7.4：账户处置（锁定 / 强制下线 / 冻结）→ 主体变更与结算账户审核 → 收款号列表与暂停收款 / 出款 → 冻结与停业分离 |
| 8 | 其余页面按规范收口 | 规范附录列了存量差距：30 个文件的保存按钮在正文、`RowActions` 用得少、抽屉宽度 10 种 |
| 9 | 关闭前的「未保存提醒」 | 规范 §7 要求，尚无公共实现 |

## 六、验证

| 项 | 结果 |
|---|---|
| `tsc --noEmit`（ops-web） | 通过 |
| eslint（改动目录） | 无 error |
| vitest（ops-web，合并后） | 1261 通过；`lib/gated-endpoints.test.ts` 整个文件跑不起来，在合并前的 `main` 上同样如此 |
| 生成文档闸门 `check-generated-docs` | 26 个生成器产物最新 |
| 界面清单 `gen-ui-catalog --check` | 通过（335 个界面） |
| 需求文档只读检查 `retired-state-words` | 失败：`SHIPPED` 出现次数超基线 1 次，来自更早的提交，与本次无关 |
| 浏览器（本地 mock，3105） | 合并前点验过：入驻审核底栏、商户「更多」菜单、套餐授予三态、支付服务表单不叠抽屉且切页签不丢、门店码换码、门店订单页签五列完整 |

## 七、环境备忘

- **`rg` 只是 zsh 函数**：Node 子进程找不到，生成文档闸门会报 `spawnSync rg ENOENT`。装一个真的 ripgrep（`brew install ripgrep`），或临时做一个调用 Claude 内置 rg 的 shim 放进 PATH。
- **本地 mock 模式**：整页刷新会卡在「暂时无法核验共享登录状态」，要点「使用其他账号登录」重新登录（任意用户名和非空密码都能进）。热更新经常不生效，改完要整页刷新才看到新代码。
- **推送认证**：这个终端没有 GitHub 登录。凭据在 `~/work/env/git/github-credentials`（只限本人读写，见同目录 README），用 `git -c credential.helper= -c "credential.helper=store --file=$HOME/work/env/git/github-credentials" push …` 让 git 自己读取。
- **线上入口**：商城运营端 `https://nearshop.umeshop.ai/ops-shop/`。注册账户在「客户管理 → 用户数据」（`/customers?tab=users`）。
