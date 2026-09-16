# TDD-库存首页入口排布

状态：**已实现** · 档位 **1**（动了 i18n 词条，没动端点 / 库表 / 权限码 / 配置）
关联需求：[进销存-需求.md](../requirements/进销存-需求.md) §一 看（INV-S7）· §二 记（INV-W1 W4 W8 · C3）· §三 查（INV-Q2 Q4）· §四 配（INV-C1 C2 · W10）
设计稿：[库存首页入口排布](https://claude.ai/artifact/KW55sBD4DFCAE92EcCVXJN)
创建：2026-09-16 · 最后更新：2026-09-16

> 需求里没有「首页怎么摆」这一条 —— 九个入口的**存在**都在需求表里，
> **排布**从来没写过。所以下面的 AC 是按本次口头需求补的（见技能文档的「临时补 AC」）。

## 验收标准

- **AC1** 打开库存首页，底部那条只剩两个写入口：进货、报损，各自左边一个图标。
- **AC2** 底部第三格是「更多」，点开**从这条自己往上伸出一段菜单**（不是另开一层弹层），
  里面是另外六个：盘点 · 调拨 · 单据 · 报表 · 库位 · 供应商。
  **只有名字** —— 没有分组标题，也没有每条一句的说明（2026-09-17 商家定的：
  这六个是这一行通用的说法，配一句解释反而像在教人认字）。顺序仍按「多久用一次」排。
- **AC6** 进货与报损是**按钮**不是文字：进货实心、报损浅底，各自带图标；
  「更多」是一枚灰底按钮，箭头随开合翻向。
- **AC3** 总览卡里那排等大文字链接不再存在；「看各店的货」挪到四个数下面，
  仍然只对开了不止一家店的商家出现。
- **AC4** 放货的地方少于两个时，弹层里的「调拨」是灰的、说明换成用不了的原因，
  点它不进调拨页；有 `biz:store:admin` 的人多给一个「去添加」的去处，没有的人不给。
- **AC5** 每条仍按**它自己那一页**的权限判（报表 `biz:customer`、库位 `biz:store:admin`），
  判不过的条目在弹层里根本不出现，而不是出现后点进去被拒。

## 不做（Out of Scope）

- **不合并那四张单**。进货和报损尤其不能合成一个带方向开关的「出入库」：
  选反了系统不报错，账朝相反方向错两倍。
- **不把库位 / 供应商搬到别的页面**。原先定好的是首页只开一个口子进库存，
  搬出去等于再开第二个口子。
- **不改这六页自己内部的文案**（「库位」这个词本身留着 —— 换掉要连三页一起换，是另一件事）。
- **不接分层收费**。跨店入口仍按 `multiStore` 判，不接 `crossStoreStats` 能力位（理由见需求 §八）。

---

## §0 对账一 · 需求 → 设计

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 底部只留进货、报损，带图标 | `stockEntries()` 的 `primary` · `pages/stock/index.vue` 的 `sh-actionbar` |
| AC2 | 「更多」伸缩菜单，六条只有名字 | `stockEntries()` 的 `more` · 页内 `.menu`（`max-height` 过渡） · 词条 `stock.more` |
| AC6 | 三枚按钮而不是三段文字 | `.sh-btn--sm` ＋ `--soft` / `--muted` 三档 · `sh-icon` 的 `plus` / `minus` / `chevronUp·Down` |
| AC3 | 取消那排链接；跨店挪到数字下面 | `stockEntries()` 的 `cross` · 总览卡模板 · 词条 `stock.crossGo` |
| AC4 | 放货的地方不足两个时调拨不可点 | `stockEntries()` 的 `blocked` · 词条 `stock.entryBlocked.transfer` `stock.goAddLocation` |
| AC5 | 按各自那一页的权限判 | `stockEntries()` 的 `can` 回调（原来那两个 `computed` 的判法搬进来，逐条不变） |

**孤立项**：无。六个入口、跨店、两个写动作，九条都有落点；没有挂不上 AC 的设计条目。

## §1 现状与影响面

- **相关现有模块**
  - `b-app/src/pages/stock/index.vue` —— 今天在这里写死两个 `computed`：`actions`（四个写动作）与 `links`（五个链接）。
  - `b-app/src/shared/stock-urgent.ts` —— **本次照它的形状做**：只回答「有哪些」，不回答「怎么摆」，也不碰 i18n，于是能在 node 里直接断言。
  - `packages/ui/src/components/sh-sheet.vue` —— 底部弹层，已有 `flush`（内容通铺到边，一行行的列表要的正是这个）。**不新建组件**。
  - `api.mStockLocations()` —— 已存在（`GET /biz/inventory/locations`），调拨页与库位页都在用。**不新增端点**。
- **可直接复用**：`sh-sheet` · `sh-go` · `sh-icon`（`plus` / `minus` / `chevronUp` 三个现成图标语义正好，**不动 `icons.ts`**）。
- **会被改到的**：库存首页本身（九个入口全部重排）。`pages/home` 的「进销存」卡**不动** —— 它引的是 `stock.entry.*`，那些词条一条都不删。
- **明确不受影响的**：六个子页面自身、所有后端端点、权限码、`stock.entry.*` 既有词条、
  `urgentStockItems()` 与工作台共用的那份。

## §2 方案

### 契约变更

- 端点：**无**
- 库表 / 字段 / 迁移号：**无**
- 权限码：**无**（沿用 `biz:stock` / `biz:customer` / `biz:store:admin`）
- 配置项：**无**
- **i18n 词条：新增 4 条，三语同步**（删除 0 条）

  | key | zh-CN |
  |---|---|
  | `stock.more` | 更多 |
  | `stock.crossGo` | 看各店的货 |
  | `stock.goAddLocation` | 去添加 |
  | `stock.entryBlocked.transfer` | 要两个放货的地方，现在只有 {n} 个 |

  ⚠️ **端上 i18n 闸门看不见动态键**（`$t(\`stock.entry.${key}\`)` 不进对账）——
  三语的键集要人自己对一遍，「闸门 0 条新增」不是证据。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `b-app/src/shared/stock-entries.ts` | 纯函数：权限 + 门店数 + 放货地点数 进，`{ primary, groups, cross }` 出。不碰 i18n、不碰 uni |
| 新增 | `b-app/tests/stock-entries.test.ts` | 五条 AC 各一个用例 |
| 修改 | `b-app/src/pages/stock/index.vue` | 两个 `computed` 换成一次调用；总览卡去掉链接排、加跨店一行；贴底条改成三枚按钮 + 一段伸缩菜单 |
| 修改 | `b-app/src/i18n/locale/{zh-CN,en,ar}.ts` | 各加上面那 4 条 |

### 关键接口

```ts
export interface StockEntry {
  key: string;
  route: string;
  /** 用不了：给一个原因（词条 key 由调用方翻）与可选的去处 */
  blocked?: { reasonKey: string; params?: Record<string, number>; route?: string };
}
export function stockEntries(ctx: {
  can: (perm: string) => boolean;
  multiStore: boolean;
  /** 除在途外能放货的地方有几个；**还没取到时给 null** —— 不知道不等于不足两个 */
  usableLocations: number | null;
}): { primary: StockEntry[]; more: StockEntry[]; cross: StockEntry | null };
```

## §5 对账三 · 实现 → 需求（测试）

`b-app/tests/stock-entries.test.ts` · 9 条全绿（`cd b-app && npx vitest run --config vitest.config.mts`）。

| AC | 测试方法 | 跑过 | 消融（撤掉实现 → 必须变红） |
|---|---|---|---|
| AC1 | `底下那条只留进货和报损` | ✅ | 把盘点加回 `primary` → **2 红** ✅ |
| AC2 | `「更多」里是六条，按多久用一次排` | ✅ | 把库位挪到第一条 → **1 红** ✅ |
| AC3 | `跨店只给多门店商家，且不在那六条里` | ✅ | 去掉 `multiStore` 判据 → **1 红** ✅ |
| AC4 | `放货的地方不足两个时调拨不可点，并给出去处` | ✅ | 去掉库位数判据 → **2 红** ✅ |
| AC4 | `没有库位权限的人不给去处，但仍要看到原因` | ✅ | 去掉 `biz:store:admin` 判据 → **1 红** ✅ |
| AC4 | `还没取到库位数时不灰掉 —— 不知道不等于不足两个` | ✅ | （同上一条的消融一起覆盖） |
| AC5 | `每条按它自己那一页的权限判` | ✅ | 去掉逐条权限过滤 → **2 红** ✅ |
| — | `一个码都没有的人：菜单是空的` | ✅ | （由 AC5 那次消融一起覆盖） |
| — | `在途不算一个放货的地方` | ✅ | （`countUsableLocations` 的边界，无独立消融） |

**六次消融全部变红，没有一条是假绿。**

### 真界面上验过（b-app H5 mock，5176）

| 验的是 | 结果 |
|---|---|
| 总览卡里那排五个链接 | **不在了**；卡里只剩四个数 |
| 「看各店的货 ›」 | 贴在四个数下沿、靠行尾（临时把 `multiStore` 置真验的，验完已还原） |
| 「有人在等」 | 单独一张卡（收货 1 · 继续盘点） |
| 贴底条 | 三枚按钮：`＋进货`（实心）｜`－报损`（浅底）｜`更多 ⌃`（灰底）。**不是三段文字** |
| 「更多」 | 点一下从这条往上伸出六行，只有名字；箭头翻成 `⌄`；再点一下收回去 |
| 菜单装得下 | 量过 `scrollHeight` = `clientHeight`，**裁了 0px**（此前 760rpx 的上限切掉了最后一条，是量出来才发现的） |
| 调拨不可用 | 临时把库位 mock 改成只剩一个：名字压暗、下面一行「要两个放货的地方，现在只有 1 个」、行尾红色「去添加 ›」，点它进的是库位页（验完 mock 已还原，`git diff` 为空） |
| 走得通 | 「更多」→ 盘点 → 盘点页 |
| 颜色没拼错 | 逐个量了 computed：`.act__t` = `rgb(179,23,16)`、`.act__t--more` = `rgb(99,103,110)`、弹层行的 `--sh-panel-pad-x` = 18.72px 与面板自身一致（**量的是算出来的值，不是读代码推的** —— 变量名拼错时整条声明静默失效） |

**没验到的**：深色模式没在界面上看。用的全是既有令牌（`--sh-sub` / `--sh-primary-text` / `--sh-hairline-soft`），
没有一处写死颜色，但这是推断不是观察。

## §6 对账二 · 设计 → 实现

```
b-app/src/i18n/locale/ar.ts                        |   4 +
 b-app/src/i18n/locale/en.ts                        |   4 +
 b-app/src/i18n/locale/zh-CN.ts                     |   8 +
 b-app/src/pages/stock/index.vue                    | 372 +++++++++++++++------
 docs/technical/README.md                           |   1 +
 ...225\214\351\235\242\346\270\205\345\215\225.md" |   3 +
 6 files changed, 292 insertions(+), 100 deletions(-)
 b-app/src/shared/stock-entries.ts      | 新增 133 行
 b-app/tests/stock-entries.test.ts     | 新增  80 行
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的：`docs/technical/README.md` | 守卫「technical 下每篇文档都出现在 README 索引里」要求的，不进索引推不上去 |
| TDD 里没有、实际改了的：`design/进销存-界面清单.md` | 生成物。`gen-inv-ui-inventory.py --check` 是 pre-push 的一道闸，改了这一屏的文案就必须重跑 |
| TDD 列了、实际没动的 | 无 |

### 偏差说明

- **一开始做成了 `sh-sheet` 底部弹层，两枚写动作是纯文字。** 商家看过之后改成
  「按钮 + 伸缩菜单」：弹层会把人圈在一件事里，而这六条是「顺手看一眼」的东西。
  已改；`sh-sheet` 不再用到。
- **`.grp` 的字号原本写了 `22rpx`**，撞上字阶守卫（只认 24/26/28/34/40/48/60）。
  后来分组标题整个去掉了，这处连同它一起没了。
- **过渡时长原本裸写 `0.22s`**，撞上动效守卫。改成 `var(--sh-t-fast)`。
- **菜单原本三组六条、每条一句说明**，商家看过之后只留名字。
  `stockEntries()` 的返回值跟着从 `groups`（分组）改成 `more`（一条平列表）——
  留着分组结构而界面上不画，等于留一段没人读的代码。

### 两道闸门现在红着，但**不是这次改红的**

| 闸门 | 红在哪 | 谁引入的 |
|---|---|---|
| `check-rtl-physical` | `goods-list/index.vue`、`stores/index.vue` 共 5 处写死左右 | `5371070f` 等提交，已在 HEAD 里；旁边的会话还在改 goods-list，数目一直在变 |
| `ui-package` 4rpx 网格 | `stores/index.vue:581` 的 `padding-top: 6rpx` | 同上（`git log -L` 查到的） |
| `typography` 字阶 | `goods-list/index.vue` 的 `.row__chev 32rpx` | 旁边会话正在改的那一版 |

这两条都在别人正在改的文件里，**没有动**。它们会挡住所有人的 push，需要那边的人或经同意后一起修。

### 顺带发现（这次没做）

`gen-inv-ui-inventory.py` **看不见动态拼出来的词条**：`$t(\`stock.entry.${e.key}\`)` 这类一条都不进清单。
于是清单上这一屏没有「盘点 / 调拨 / 单据 / 报表 / 库位 / 供应商」中的任何一个，
而它们正是这一屏上最显眼的六个词；`stock.entryBlocked.transfer` 同样不在。
这是既有盲点（那九条一直都不在），不是这次改出来的。**修它要动共享的生成器**，另开一件事做。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-16 | 设计稿四版，商家确认；开始实现 |
| 2026-09-17 | 商家看过真界面后两处返工：①文字改按钮 ②弹层改伸缩菜单 ③菜单里只留名字 |
| 2026-09-17 | 已实现。跑过的闸门：`vue-tsc`（b-app，exit 0）· `vitest`（b-app 40 条全绿）· `gen-ui-catalog.py --check` · `gen-inv-ui-inventory.py --check` · `check-i18n-orphan.mjs --check`（0 回归）· `check-shared-guards.mjs --check`（本次引入的两条已修；剩下一条是 HEAD 里别人的） |
