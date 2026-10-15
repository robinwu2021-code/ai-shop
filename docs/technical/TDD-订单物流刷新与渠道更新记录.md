# TDD-订单物流刷新与渠道更新记录

> 状态：已实现、待人工联调 · 关联需求：[物流功能清单](../requirements/物流-功能清单.md) L-AC-01～04 · 更新：2026-10-15

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| L-AC-01 | 手动刷新后同步更新订单 | `LogisticsPortImpl.track` → `shipmentSnapshotOf` → B/C `ShipmentSnapshotConverger`；订单列表直读 `ord_sub_order` |
| L-AC-02 | 微信/快递100异步更新订单 | `WaybillProgress` 发 `LGS_SHIPMENT_SNAPSHOT`，`ShipmentSnapshotConsumer` 调同一收敛器 |
| L-AC-03 | 测试期去掉手动查询缓存 | `manual-refresh-cache-minutes=0`；普通微信页面读仍为 10 分钟，快递100保留服务商限频 |
| L-AC-04 | 记录渠道及更新细节 | `V421` 的 `lgs_update_log`、`LogisticsPort.updatesOf`、B 端详情更新记录；`ord_status_log.label` 写渠道 |

没有未落点 AC。新增审计表、查询端点和 i18n 均服务 L-AC-04。

## §1 现状与影响面

订单列表、详情本来直接查交易表，没有 `@Cacheable` 或前端订单列表缓存；线上安全响应已有 `Cache-Control: no-cache, no-store`。此前微信探测的 `wx_status_checked_at` 10 分钟闸会拦住再次点“刷新”，且 B 端同步收敛已实现，C 端刷新尚未调用收敛器。异步快递100轨迹虽发订单快照，快照不携带渠道；微信先报签收、快递100稍后补节点时，必须继续校验最早节点与本次发货时间，不能把旧运单签收当作本单签收。

影响：B 端详情刷新和记录展示、C 端刷新、物流渠道查询与推送、交易订单状态流水。普通订单列表仍从交易表读取；退款、积分与非快递履约不改。

## §2 方案与执行顺序

| 顺序 | 动作 | 位置 | 验收点 |
|---|---|---|---|
| 1 | 手动刷新绕过微信读时缓存，微信优先；无结果或只有签收状态时查询快递100 | `LogisticsPortImpl`、`LogisticsProperties`、`TraceProvider` | 手动刷新实际调用渠道，普通读仍节流 |
| 2 | 快递100按公司+单号记录查询占位并执行 30 分钟/48 次限频，单次查询预算 3 秒 | `WaybillUpdateAudit`、`Kuaidi100TraceProvider` | 不因测试期开缓存绕过服务商配额 |
| 3 | 物流状态变更与新增轨迹在同一事务发含 `source` 的快照；异步消费者复用订单收敛器 | `WaybillProgress`、`ShipmentSnapshotConsumer` | 旧发货代际和旧轨迹均不能完成当前订单 |
| 4 | B/C 刷新读取当前运单快照后在响应前同步收敛交易；有效签收自动完成 | `MerchantOrderServiceImpl`、`OrderServiceImpl`、`ShipmentSnapshotConvergerImpl` | B/C 下一次列表读取相同状态 |
| 5 | 新增仅当前运单可见的更新记录，并在交易状态流水写中文来源 | `V421`、`WaybillUpdateAudit`、B 端 API/详情 | 显示时间、微信/快递100、查询/推送、前后状态、节点数、结果 |

契约：新增 `GET /biz/order/{subOrderNo}/logistics-updates`（复用 `biz:order:view` 和门店归属）；响应为 `UpdateRecord[]`。新增表 `lgs_update_log` 为追加写，保留渠道、模式、状态前后、结果和时刻，不存手机号与原始回调。新增配置 `shop.logistics.manual-refresh-cache-minutes`，测试期默认 0，验收后可设 10。新增 B 端三语更新记录词条，不改权限码。旧出站事件没有 `source` 时仍可消费，流水标为“渠道未标明”。

## §3 选型

| 方案 | 取舍 |
|---|---|
| **独立追加更新表 + 订单原状态流水（采用）** | 物流域不读写交易表；查询和推送都可留痕，状态真正迁移时由交易域记来源 |
| 只在现有节点表记渠道 | 无法记录微信只给状态、无节点、查询失败和限频 |
| 直接把物流 `DELIVERED` 写成订单 `COMPLETED` | 旧运单可能提前触发结算；保留当前发货代际、运单号和发货后签收节点校验 |

不可逆的追加审计表与跨域来源传递见 [ADR-036](ADR/ADR-036-物流渠道更新留痕与订单收敛.md)。

## §4 风险与边界

| 风险 | 缓解 |
|---|---|
| 微信仅返回签收状态、不提供节点时间 | 快递100短预算补查；无可信节点仍显示签收待核实，不自动完成 |
| 老运单已有签收历史 | 最早轨迹必须不早于本次发货；更正运单后旧快照无法匹配 |
| 人工高频刷新耗尽快递100额度 | 公司+单号维度 30 分钟/48 次限频；微信测试期不使用读时缓存 |
| 新表上线前没有历史记录 | 只展示上线后新增记录，不推测旧查询来源；旧推送仍保留原始回调审计 |
| 顺丰/中通签收后手机号已清理 | 同次刷新在请求栈里保留手机号用于补查；旧已签收单若已清理仍需依赖订阅轨迹或更正运单 |

## §5 对账三 · 实现 → 需求

| AC | 定向验证 | 结果 |
|---|---|---|
| L-AC-01 | `ShipmentSnapshotConvergerImplTest#laterDatedProofReplacesStatusOnlyTimestampAndCompletesOrder`；B/C 服务编译 | 通过 |
| L-AC-02 | `WaybillProgressTest#snapshotDistinguishesDatedSignatureFromStatusOnlySignature`、`ShipmentSnapshotConsumerTest#asyncSnapshotPassesKuaidi100SourceToOrderConvergence` | 通过 |
| L-AC-03 | `LogisticsPortImplTest#explicitRefreshBypassesWechatReadCacheDuringTesting`、`#deliveredStatusUsesK100ForDatedProofWhenRefreshIsRequested` | 通过；消融时把刷新间隔临时改回 10 分钟，该用例按预期失败（Expected 1 but was 0），还原后通过 |
| L-AC-04 | `WaybillProgressTest#transitionCarriesCarrierAndNumber`、B 端 `vue-tsc`、`SchemaGeneratorTest` | 通过 |

定向 Maven 用例、B 端 `vue-tsc`、界面清单和 SQL 方言扫描退出码均为 0；方言扫描提示既有 `V417` 两处待偿还欠账，本次 `V421` 未新增命中。`SchemaDriftTest` 的既有 `ord_shipment` 生成列解析误报 `GENERATED` 仍失败；HEAD 版 `schema-test.sql` 已含该列，本次迁移的 `SchemaGeneratorTest` 通过。按当前人工联调约束未跑全量或并发测试。

## §6 对账二 · 设计 → 实现

提交后以 `git show --stat` 核对；预期文件组为物流查询/审计/迁移、交易收敛与 B/C 刷新、B 端契约/详情/i18n、定向测试及生成的 `schema-test.sql`。偏差：订单列表没有应用层缓存，因此保留其数据库读取，仅取消测试期手动微信查询间隔。

## §7 人工联调复盘（2026-10-15）

最新两笔订单刷新后交易仍为 `FULFILLING`，但原因不同：`O261015090032` 的运单签收于 10 月 8 日，早于本单 10 月 15 日发货，旧运单保护按设计阻止自动完成；`O261015100041` 的运单仍为 `IN_TRANSIT`，尚无签收。B 端列表主标签原先只映射交易 `FULFILLING` 为“已发货”，即使物流栏已变化，也让人工误判为刷新无效。修正为列表主标签展示物流阶段，已签收但交易仍在途时显示“签收待核实”；详情对签收早于下单的运单明确提示核对和更正。交易状态及自动完成的可信签收校验不改变。
