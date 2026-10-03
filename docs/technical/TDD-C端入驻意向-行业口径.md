# TDD-C端入驻意向-行业口径

状态：**已实现**（2026-09-29，后端 7d75f72c4 · 端上紧随其后一条）
档位：1（动了库表一列、主数据端点的返回结构、i18n；不新建域、不改进件与审核的判定）
关联需求：用户 2026-09-28 原话「给出更多的行业列表，让用户选择，甚至可以手动填写，这样可以收集更多数据」
创建：2026-09-29

## 问题：一个 `enabled` 被当成两把尺用

`sys_industry.enabled` 今天同时回答着两个问题：

| 问的是 | 谁在问 | 一期的答案 |
|---|---|---|
| 平台**能不能接**这一类商家（执照经营范围） | B 端进件、运营审核 | 只有 `RETAIL` 与 `LIFE_SERVICE` |
| 商家**能不能表达**想做这一类的意向 | C 端入驻意向屏 | 应该是全部 |

`MasterDataService.snapshot()` 按 `enabled = 1` 过滤，C 端照它渲染，
于是入驻意向屏上只有两个选项 —— 而**意向表的价值恰恰在于收集平台还接不了的那些**。
想开餐饮的人今天只能选「线下零售」，那条信息在入库的一刻就丢了。

**后端不拦。** `OpsServiceImpl` 建单时 `apply.setIndustry(cmd.industry())` 没有白名单校验，
C 端不传 `subject` 时 `requireSubjectAllowedByIndustry` 也直接 return。
所以这不是「放开一条校验」，是端上少拿了数据。

## §0 对账一 · 需求 → 设计

| AC | 需求（用户原话） | 落点 |
|---|---|---|
| AC1 | 「给出更多的行业列表」 | `MasterDataVO` 新增 `intentIndustries`（**全量**，带 `open` 标志）；`industries` 一个字节不动，进件那条路的口径不变 |
| AC2 | 同上，但不能骗人 | 端上选中 `open=false` 的一档时给一句「这一类还没开放，我们会先记下来」，**不拦提交** |
| AC3 | 「甚至可以手动填写」 | 选「其他」时出现一个 24 字的手填框，落到 `mch_entity_apply.industry_note` |
| AC4 | 「这样可以收集更多数据」 | 运营端意向清单看得到手填那句话；否则收了等于没收 |
| AC5 | —（设计自带的收口） | `industry_note` 只在 `industry = OTHER` 时有意义；提交别的行业时后端置空 —— 否则改一次行业就留下一句对不上的话 |

## §1 模块设计

| 动作 | 文件 |
|---|---|
| 新增 | `backend/shop-app/src/main/resources/db/migration/V360__apply_industry_note.sql` |
| 修改 | `backend/shop-core/.../platform/entity/MchEntityApply.java`（+`industryNote`） |
| 修改 | `backend/shop-core/.../platform/dto/MasterDataVO.java`（+`intentIndustries` + `record IntentIndustry`） |
| 修改 | `backend/shop-core/.../platform/impl/MasterDataServiceImpl.java`（一次查全量，两个列表从同一份切） |
| 修改 | `backend/shop-core/.../platform/OpsService.java`（两个 cmd +`industryNote`） |
| 修改 | `backend/shop-core/.../platform/impl/OpsServiceImpl.java`（建单 / 改单写入 + AC5 置空） |
| 修改 | `backend/shop-core/.../platform/dto/OpsVOs.java`（`MerchantApplyVO` +`industryNote`） |
| 修改 | `c-app/src/pages/me/index.vue`（行业改全量 + 未开放提示 + 其他手填） |
| 修改 | `c-app/src/api/types.ts` · `c-app/src/i18n/locale/{zh-CN,en,ar}.ts` |
| 修改 | `ops-web/app/merchants/apply-tab.tsx`（展示手填行业） |
| 新增 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/MerchantApplyIntentIndustryFlowTest.java` |

## §2 对账二 · 设计 → 实现

`git diff --stat`（剔掉同事在两条提交之间插入的 `SettleStats*` 三份）：

```
backend/shop-app/.../portal/biz/BizMerchantController.java        |   2 +
backend/shop-app/.../portal/mp/MpCatalogController.java           |  10 +-
backend/shop-app/.../db/migration/V360__apply_industry_note.sql   |  12 ++
backend/shop-app/.../portal/biz/MerchantStatusMappingTest.java    |   4 +-
backend/shop-app/.../MerchantApplyIntentIndustryFlowTest.java     | 168 +++
backend/shop-app/.../scenario/OnBehalfMerchantFlowTest.java       |   4 +-
backend/shop-app/.../scenario/QualificationChainFlowTest.java     |   2 +-
backend/shop-app/src/test/resources/schema-test.sql               |   1 +
backend/shop-core/.../platform/OpsService.java                    |   7 +
backend/shop-core/.../platform/api/ops/OpsMerchantApplyController |   2 +
backend/shop-core/.../platform/dto/MasterDataVO.java              |  17 +
backend/shop-core/.../platform/dto/OpsVOs.java                    |   7 +
backend/shop-core/.../platform/entity/MchEntityApply.java         |  17 +
backend/shop-core/.../platform/impl/MasterDataServiceImpl.java    |  19 +-
backend/shop-core/.../platform/impl/OpsServiceImpl.java           |  18 +
c-app/src/pages/me/index.vue                                      |  58 +-
c-app/src/shared/apply-industry.ts                                |  43 +
c-app/tests/apply-industry.test.ts                                |  79 +
c-app/src/api/mocks/group.ts                                      |  13 +
c-app/src/i18n/locale/{zh-CN,en,ar}.ts                            |   7 +
b-app/src/api/mocks/store.ts                                      |  11 +
ops-web/app/merchants/apply-tab.tsx                               |   8 +
ops-web/app/merchants/copy.ts                                     |   4 +
ops-web/lib/api/master-data.ts                                    |  18 +-
ops-web/lib/types/merchant.ts                                     |   7 +
packages/shared/src/types/merchant.ts                             |  35 +
```

与 §1 逐行比：**多了四份、少了零份**，四份都写在 §4。

## §3 对账三 · 实现 → 需求

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 | `MerchantApplyIntentIndustryFlowTest#intentIndustriesCoversDisabledOnes` | ✓ |
| AC2 | 同上（停用的四档断言 `open=false`，RETAIL 断言 `open=true`）+ `apply-industry.test.ts`「未开放的那一档要给提示，已开放的不给」 | ✓ |
| AC3 | `#industryNoteIsPersistedAndReachesOps` | ✓ |
| AC4 | 同上（走 `/mp/merchant/apply` 回读 VO 的 `industryNote`）；运营端那一栏由 `apply-tab.tsx` 渲染 | ✓ |
| AC5 | `#industryNoteClearedWhenIndustryIsNotOther` | ✓ |
| —  | `#disabledIndustryIsStillAccepted`（未开放照收，拦下来就是又拿准入的尺子量意向） | ✓ |

真实输出（HEAD 干净副本 —— 主工作区里同事的在建代码让 `pay-channel` 测试编译不过）：

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- MerchantApplyIntentIndustryFlowTest
Test Files  1 passed (1)   Tests  5 passed (5)   -- c-app/tests/apply-industry.test.ts
```

**消融三次，都红在该红的那一行**：

| 撤掉什么 | 红的是 |
|---|---|
| `MasterDataServiceImpl` 意向那一支改回 `.eq(enabled, true)` | AC1「意向口径要**严格更宽** —— 一样宽就说明还在按 enabled 过滤」 |
| `normalizeIndustryNote` 去掉 `OTHER` 前置判断 | AC5「选了具体行业，手填那句话必须没了」 |
| `apply-industry.ts` 去掉「优先用意向口径」那一行 | 端上 3 条（更宽 / 未开放要提示 / 取名不露裸码）—— 不是只红一条 |

AC1 的断言是 `7 > 2` 且真子集，**两个数都先断言非零**：
「意向 ⊇ 进件」对两个空集同样成立，那是最容易通过的一种假绿。

## §4 偏差说明

四处多出来的，都不是顺手：

1. **`backend/shop-app/src/test/resources/schema-test.sql`** —— 加列要改两处。
   测试跑的是这份 schema 不是 Flyway（H2 下 `V1__baseline.sql` 是 MySQL 语法，
   `flyway.enabled=false`）。只加迁移的话四条用例全报
   `Column "industry_note" not found`，而报错与「行业口径」毫不相干。
   这个坑与 [[migration-needs-entity-field]] 是同一族：一件事要登记在几处。

2. **`c-app/src/shared/apply-industry.ts` + 它的测试** —— §1 原本打算把判断写在 SFC 里。
   那样只能写「源码里包含某个字符串」那种断言（本仓库既有的 `merchant-recruit.test.ts`
   就是这么写的），而它改个变量名就假绿。三条纯函数抽出来之后测的是行为，
   消融一行能红三条。

3. **`ops-web/lib/api/master-data.ts`** —— 实现中发现的**同一个根因的另一面**：
   审核台的 `label.industry` 也只查进件口径，于是报了餐饮的那张单在运营眼里是裸码
   `CATERING`。不修的话 AC4「收集更多数据」只兑现了一半：数据进了库，读的人看不懂。

4. **`b-app/src/api/mocks/store.ts`** —— `MasterData.intentIndustries` 是必填，
   b-app 的替身少这一个字段就与真接口不是同一个形状（vue-tsc 抓出来的）。
   B 端不读它，但替身要与服务端一致。

一处**没有**做：没有往 `sys_industry` 加行。用户说的「更多的行业列表」在这一版里是
「把已有的七档全放出来（此前端上只看得到两档）+ 其他手填」。往那张表加行等于让
「平台能接什么」跟着「有人想做什么」变 —— 它每一行都挂着小微白名单、积分强制与
执照经营范围的判定。手填那一格已经无上限地收集数据了。

---

## §5 追加 · 下载引导的二维码（2026-09-29，提交 c125dcfb4）

用户五点里的最后一项：「可以复制地址链接，或者**二维码**」。复制早就有了，二维码没做。

### 先查了能不能复用

仓库里**确实有二维码**，但那是微信**小程序码**（`StoreQrcodeServiceImpl` 调微信接口出图，
落在 `mch_store.acode_base64`）—— 它只能打开小程序，编不了任意 URL；
后端也没有通用编码器（`zxing` 零处）。所以复用走不通，回到端上画。

> ⚠️ 我最初给用户的 A/B 两案里，B 是「后端出图」，理由是「现成的」——
> 那个前提是错的，查过之后才发现现成的那份编不了 URL。**问「能不能复用」之前先查**。

### 关键决定

| 决定 | 为什么 |
|---|---|
| `qrcode-generator`（零依赖） | `qrcode` 要拖 pngjs / yargs / dijkstrajs |
| **只到点阵，不画 canvas** | 三端 canvas 不是同一套 API，画上去要写三份；点阵铺 `<view>` 三端同一条路径 |
| 格子尺寸**算出来** | 小程序端 `aspect-ratio` 看基础库版本；`flex:1` 分不出整数像素时相邻格差半像素，密的码扫不出来 |
| 颜色写死黑白 + 自带白底留白 | 这两样是**扫得出来的前提**。换肤把码变成主题色，界面上看着仍然有码，只是扫不出来 |
| 默认收着 | 人正拿着这台手机看这一屏，自己扫不了自己的屏幕；它的用处是给旁边的人扫 |
| 抽成 `biz-app-download` | 下载区此前在报名表底部与提交完成页各一份，加一样东西要改两处 |

### 对账三 · 测试与消融

| 断言 | 测试 |
|---|---|
| 空串给空点阵 | `qrcode.test.ts`「不画一个『编了空字符串』的合法码」 |
| 版本跟内容长度走 | 同上（更长的内容要更大的点阵；写死版本会抛 overflow） |
| 内容不同点阵就不同 | 同上（否则「画出来了」证明不了「编的是它」） |
| 三个定位点在三个角 | 同上（**右下角必须没有** —— 把「四角都画了」这种错实现排除掉） |
| 查看态显示行业名不是码 | `apply-view-industry.test.ts`（挂载整页） |

**消融两次，两次都先给了假绿**：

1. 删掉空串兜底 → 第一条红。✓
2. 删掉查看态那句 `ensureMasterData` → 第一版用例**照样通过**。
   原因：没登录时 `onShow` 里 `if (user.isLogin)` 不成立，`applyStatus` 恒空，
   点开店走的是**新建报名表**那条路，而那条路自己也拉主数据 ——
   用例从头到尾没进过查看态。补上登录、并加一句「先确认真的在查看态」的锚点之后才红。

### 顺带修的真缺陷

查看意向那一屏的「店铺类型」显示裸码 `FRESH`。取名函数拿到空列表时按约定回退成码，
**那一步是对的**；错的是这一屏没把料备齐 —— 主数据只有「打开报名表」那条路会拉。
是在 H5 mock 上截图时肉眼撞见的，单测覆盖不到「这一屏有没有去拉数据」。

