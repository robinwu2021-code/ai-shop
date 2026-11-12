# TDD-餐饮渠道绑定停用即时下架

状态：已实现
关联需求：无既有 PRD 条目（`docs/requirements/` 未找到菜单渠道绑定停用的需求），口头需求整理为下方 AC
创建：2026-11-12 · 最后更新：2026-11-12

档位：0（选用不动契约的等价方案，未改端点 / 库表 / 权限码 / i18n / 配置 / 对外 JSON）。
上游会话按 1 档预估并要求留 TDD，本文件保留四节作为设计与对账记录。

## AC

- **AC1** Given 菜单 M 已发布并绑定外卖连接 A，When 运营把 A 的菜单渠道绑定停用，Then 不需要重新发布菜单，A 的下一次目录同步就把 M 按空来源下架（无商品、无售卖时段）。
- **AC2** Given M 同时绑定连接 A、B，When 只停用 A 的绑定，Then B 的目录照常可售，M 的当前菜单证明仍然有效。
- **AC3** Given A 的绑定已停用（或被改绑到别的门店），When 价格策略按该绑定号取绑定事实，Then 拒绝（CONFLICT），不能借冻结快照里的 ACTIVE 继续售卖。
- **AC4** 停用绑定发出的 `FOOD_MENU_CHANNEL_BINDING_CHANGED` 仍走现有 CHANGE 通知 → `onPublishedScope` → 重采集链路，不再因当前证明被拒而在通知队列里无限重试。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | `FoodCatalogSourcePortImpl#capture`：冻结快照中匹配本连接的 ACTIVE 绑定，再经 `FoodLiveChannelBinding#live` 读本地绑定行；不再是同一条 ACTIVE 绑定 → 走已有空来源分支 |
| AC2 | `ConfigRevisionServiceImpl#verifyMenuResourceLocal`：CURRENT 证明不再逐条核对冻结绑定（该核对按连接下沉到 AC1/AC3 的两处）；发布 / 历史重验路径 `revalidateHistoricalMenuLocal` 保持原样 |
| AC3 | `FoodCatalogBindingServiceImpl#capture`：在冻结绑定匹配后追加 `FoodLiveChannelBinding#live`，否则 CONFLICT |
| AC4 | 无代码改动：`FoodCatalogSourceEventConsumer`（已有）→ `SourceNoticeServiceImpl#recoverDue` 调 `authority.current`，现因 AC2 返回 VALID → `CommerceRecoveryServiceImpl#applyPublished` 置 STALE 并排期同步 → 自动同步预览重采集，内容变化的连接才推送 |

**孤立项**：无。

## §1 现状与影响面

- 本分支上一提交 `c29580ba9` 在 CURRENT 菜单证明里要求「快照里每条冻结绑定都仍是本地 ACTIVE 行」。证明不带连接维度，于是：
  1. 停用 A 的绑定后，M 对**所有**连接的当前证明都失败 —— B 渠道也一起被挡（与「同菜单其它渠道照常」相反）；
  2. commerce 处理绑定变更通知时 `authority.current` 抛错，通知每分钟重试，A 平台上的旧目录一直保留到重新发布。
- `dc2950ef6` 已让采集把「本连接无 ACTIVE 绑定」的来源按空来源下架，但只看冻结快照，所以必须先重新发布。
- 可直接复用：空来源下架分支（`dc2950ef6`）、CHANGE 通知重采集链路、`MenuChannelBindingService#getBinding`（已有，免数据权限读）。
- 会被改到的：CURRENT 菜单证明语义（不再因绑定失效整体拒绝）；`FoodCatalogBindingServiceImpl` 与 `FoodCatalogSourcePortImpl` 构造参数各多一个 `MenuChannelBindingService`。
- 明确不受影响的：commerce 撤回契约（`ChannelSourceWithdrawalProvider`、`cim_source_withdrawal`、`SourceWithdrawalPolicy`）一行未动；整份菜单停用（`FOOD_MENU_DISABLED` → WITHDRAWAL）不变；渠道来单 `FoodChannelSourceFactsPortImpl` 仍按冻结快照收单（产品待定项，见交接笔记）。

## §2 方案

### 选型

| 方案 | 结论 |
|---|---|
| A. commerce 撤回键加连接：`ChannelSourceWithdrawalProvider.Query` 加 connectionNo、`cim_source_withdrawal` 加列改唯一键（DDL 迁移，166 需 root 执行）、通知按连接过滤 scope、resolver/重验全链路带连接 | ❌ 改 spi + 表 + 三处撤回逻辑；而且仍要先解决「CURRENT 证明对所有连接都失败」，否则 B 渠道一样被挡 |
| B. 绑定活性按连接核对：CURRENT 证明只回答「菜单是否当前」，「这条绑定是否还活」由按连接的两个消费者（采集、价格绑定）各自读本地行 | ✅ 采用：不动契约，复用已有空来源分支与 CHANGE 重采集链路，顺带修好 B 渠道被连坐 |

### 契约变更
- 端点：无 · 库表：无 · 权限码：无 · i18n：无 · 配置项：无

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `food-core/.../service/impl/ConfigRevisionServiceImpl.java` | CURRENT 证明去掉 `requireLiveChannelBindings`（发布 / 历史重验仍保留） |
| 新增 | `food-commerce/.../policy/FoodLiveChannelBinding.java` | 冻结绑定 vs 本地行：同号、同菜单 / 门店 / 渠道 / 账号 / 外部门店且 ACTIVE 才算活 |
| 修改 | `food-commerce/.../port/FoodCatalogSourcePortImpl.java` | 匹配到本连接的冻结绑定后核对活性，不活 → 空来源 |
| 修改 | `food-commerce/.../service/impl/FoodCatalogBindingServiceImpl.java` | 冻结绑定匹配后核对活性，不活 → CONFLICT |
| 修改 | `food-commerce/.../config/FoodCatalogSourceAutoConfiguration.java`、`FoodCatalogBindingAutoConfiguration.java` | 注入 `MenuChannelBindingService`（`ObjectProvider`，缺失时使用处 fail-closed） |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 | `FoodCatalogSourceCaptureTest#bindingDisabledAfterPublicationDelistsTheMenuFromThisConnectionWithoutWaitingForRepublish` | ✅ 见下 |
| AC2 | `CatalogPublicationServiceTest#currentMenuProofSurvivesADisabledOrReboundBindingSoOtherChannelsKeepSelling`；`FoodCatalogSourceCaptureTest#menuStillBoundToThisConnectionKeepsGoingThroughItsChannelPricePolicy` | ✅ |
| AC3 | `FoodCatalogBindingServiceTest#rejectsAFrozenBindingWhoseLocalRowWasDisabledOrReboundAfterPublication` | ✅ |
| AC4 | 现有 `FoodCatalogWithdrawalEventTest#channelBindingChangeRequestsAMenuRecaptureWithoutClaimingWithdrawal` + commerce `SourceNotice*` 测试（链路未改，AC2 让 `authority.current` 不再抛） | ✅ |

红灯证据：实现前，AC2 测试在 `verifyMenuResource(current)` 处抛 CONFLICT；AC1/AC3 测试因缺少活性核对（构造参数尚不存在）编译失败。

运行结果见 §6。

## §6 对账二 · 设计 → 实现

| 文件 | 与 §2 对应 |
|---|---|
| `food-core/.../ConfigRevisionServiceImpl.java` | ✅ CURRENT 证明去掉绑定整体核对 |
| `food-commerce/.../policy/FoodLiveChannelBinding.java`（新增） | ✅ |
| `food-commerce/.../port/FoodCatalogSourcePortImpl.java` | ✅ |
| `food-commerce/.../service/impl/FoodCatalogBindingServiceImpl.java` | ✅ |
| `food-commerce/.../config/FoodCatalogSourceAutoConfiguration.java`、`FoodCatalogBindingAutoConfiguration.java` | ✅ |
| 测试：`CatalogPublicationServiceTest`（原「CURRENT 证明拒绝停用绑定」改为「证明保持有效」）、`FoodCatalogSourceCaptureTest`、`FoodCatalogBindingServiceTest` | ✅ |

偏差说明：无。

运行（`backend` 目录，整个 reactor，五个 profile，`-o`）：
`-Dtest='ai/neargo/shop/food/**,**/Food*,**/SourceNotice*,**/*Withdrawal*,**/CatalogSource*,!FoodMigrationPackagingIT,!**/*Native*'`
→ BUILD SUCCESS，合计 1320 个，Failures 0 / Errors 0 / Skipped 62；其中 commerce 撤回与来源通知 22 个
（`SourceWithdrawalTransportTest`、`ChannelPreparedWithdrawalTest`、`ExplicitWithdrawalNoticeTest`、`SourceNoticeRecoveryTest`、`CatalogSourceDispositionResolverTest`、`MockCatalogWithdrawalTest`）。
