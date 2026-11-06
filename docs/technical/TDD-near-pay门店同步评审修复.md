# TDD · near-pay 门店同步评审修复

状态：已实现（2026-11-06）
档位：1（新增权限码 `merchant:store:nearpay-sync`、迁移 V632）
依据：2026-11-06 对 `codex/ai-shop-codex`（959289faf）的代码评审；原设计见 near-pay「门店信息同步设计」§5。

## §0 需求 → 设计

| # | 评审问题（AC） | 落点 |
|---|---|---|
| A1 | 同一秒内超过一批（主体改名波及 500+ 门店）时水位不再前进，之后的变更全部漏掉 | `StoreSnapshotReader.changedAfter(Cursor)` 按 (changed_at, id) 复合游标；`StoreSyncPublisher.scan` 满批推进到最后一行，追平后回看 5 秒 |
| A2 | 运营「全量同步」对已同步过（或曾被 4xx 拒绝）的修订不生效，却返回 queued=N | `publishAll` 每次请求带独立请求号：`NPS:<门店>:<修订>:FULL:<请求号>`；超过 `sys_outbox.event_no` 64 位时换稳定哈希 |
| A3 | 水位用本机 UTC，变更时间用 JVM 时区，口径不一 | 游标只取数据库值；首次扫描起点用 `SELECT CURRENT_TIMESTAMP` 往前一天 |
| A4 | 定时扫描没有分布式锁，多实例重复扫 | `tick()` 经 ShedLock `LockingTaskExecutor`，锁名 `nearpay-store-sync`，最长持有 = 间隔 − 5 秒 |
| A5 | 同步接口借用封店权限码；参数错误抛 IllegalArgumentException | 新码 `merchant:store:nearpay-sync`（只给 SUPER_ADMIN）；参数错误抛 `BizException(BAD_REQUEST)` |
| A6 | `OpsStoreSyncController` 不在 api / portal 包下 | 移到 `ai.neargo.shop.merchant.api.ops` |

## §1 契约变化

- 权限码：`merchant:store:nearpay-sync`（`Perms.MERCHANT_STORE_NEARPAY_SYNC`），功能点 `ACT__MERCHANT_STORE_NEARPAY_SYNC`（ACTION，OPS_MERCHANT），授权 SUPER_ADMIN。BD 失去这个接口。
- 迁移：`V632__store_nearpay_sync_perm.sql`（只插种子，幂等）。
- 接口路径与响应形状不变：`POST /ops/stores/nearpay-sync` → `{enabled, queued}`；`queued` 现在是本次真正入队的条数。
- outbox 事件号：增量不变；全量新增 `:FULL:<请求号>` 后缀。消费端按门店号读当前快照，不解析事件号。

## §2 模块设计

| 文件 | 改动 |
|---|---|
| `shop-merchant/.../paysync/StoreSnapshotReader.java` | `Changed` 加 `id`、`changedAt`；新增 `Cursor`、`changedAfter`、`dbNow`；`millis(LocalDateTime)` |
| `shop-merchant/.../paysync/StoreSyncPublisher.java` | 复合游标、回看、全量请求号、`fit()`、`tick()` 加锁 |
| `shop-merchant/.../api/ops/OpsStoreSyncController.java` | 由 paysync 包移入；新权限码；BizException |
| `shop-base-auth/.../Perms.java` | 新常量 |
| `shop-app/.../db/migration/V632__store_nearpay_sync_perm.sql` | 功能点 + 超管授权 |
| `ops-web/lib/perm-map.ts` | 登记新码 |
| 生成物 | `schema-test.sql`、角色×端点矩阵、权限文档、术语表 |

## §5 测试与消融

| AC | 测试 | 消融 |
|---|---|---|
| A1 | `StoreSyncPublisherTest.fullBatchAtSameInstantAdvancesByCompositeCursor` | 满批改回「回看」→ 红 |
| A2 | `StoreSyncPublisherTest.fullSyncUsesFreshEventNumbersPerRequest` | 去掉 `:FULL:` 请求号 → 红 |
| A3 | `StoreSyncPublisherTest.caughtUpRewindsFiveSecondsFromDatabaseTime` | — |
| A4 | `StoreSyncPublisherTest.timedScanRunsUnderDistributedLock` | — |
| A5/A6 | `OpsEndpointPermTest`、`OpsPermConfigFlowTest`、`SchemaDriftTest` 绿；`ArchitectureTest.controllersInDomainApiOrPortal` 由红转绿 | — |

ArchitectureTest 仍有 5 条红、BizEndpointPermTest 1 条红，都来自进销存 / 餐饮那批合并（如 `ExecutionStockRetentionRecoveryJob#scan`、`FoodStockProjectionServiceImpl`），与本修复无关，未处理。前端 `ops-perm-matrix`（商城类目 4 个端点未登记）与 `check-ops-orphan`（commerce x 端点）也是原有欠账。

## 补记 · 2026-11-06 门店同步带资金路径（fundsPath）

near-pay 解析门店收款时，要求门店投影的 `funds_path` 是 DIRECT 或 AGGREGATED，为空就拒绝（PAY_FORBIDDEN）。而 ai-shop 的门店同步从来没发过这个字段，「台北早餐」申请支付服务之后仍然开通不了收款。

- 新配置：`shop.nearpay.store-sync.funds-path`，默认 `DIRECT`，只接受 DIRECT / AGGREGATED，其他值启动时报错。用户决定（原话「目前默认都是直付」）：不读 ai-shop 主体的 `funds_mode`，因为那个字段还决定 B 端价格展示、结算单和售后。
- 门店资料（profile）加上 `fundsPath`，参与 profileHash 计算，「申请支付服务」里的门店摘要也随之一致。
- 修订号 = 变更时间毫秒 + `PROFILE_VERSION`（本次为 1）。near-pay 对同一修订号、不同摘要一律判冲突；资料结构每加一个字段，这个数就加一，否则已经同步过的门店再也推不过去。
- 上线后要触发一次全量重推（运营 `POST /ops/stores/nearpay-sync`，超管；每次请求用独立事件号），已有门店才会带上 fundsPath。
- 测试：`NearPayStoreSyncTest` 的规范摘要用例和 `fundsPathIsConfigurableAndValidated`。消融：去掉 fundsPath、把版本号改回 0，测试都会变红。
