# TDD-支付服务-退款申请人贯通

状态：本批已实现并验证 · 2026-10-29 · 档位：1
基线：379fa1bec · 隔离分支 codex/pay-business-closure
依据：支付完整设计 AC17–AC19、I3 偏差6、退款统一入口收尾，以及用户“按照建议继续”。

## §0 需求与验收

| AC | 业务行为 | 落点 |
|---|---|---|
| A1 | 商家同意/确认退款/收货触发退款，申请人取登录账户，不取商户号或请求体 | BizAfterSaleController / AfterSaleService |
| A2 | 退款申请人取原售后首次 REFUNDING 决策日志；重试和后台恢复不改写 | RefundRequesterService / 原不可变状态日志 |
| A3 | 普通、行业、积分现金退款将申请人传到 pay_refund 和 pay_approval，申请人不能参与任一轮复核 | RefundRequest / prepareRefund / RefundService |
| A4 | 系统自动退款有明确系统来源；历史无真实人员证据保持未知标记，不推测人名 | 身份解析与兼容路径 |
| A5 | 补偿后新售后重试记录新申请人，原退款号及申请人保持不变 | retryCompensatedRefund |

## §1 现状与设计

B端当前只传 merchantNo，状态日志也记商户号；OPS裁决已传实际账户。支付端统一退款申请人统一记 AFTER_SALE:单号。现有 ord_status_log 按售后聚合、不可变追加，已在退款决策事务内持久记录操作人与状态迁移。

方案：复用首次进入 REFUNDING 的售后聚合日志，不新增身份表/列。另建身份快照表或扩多张执行表会重复维护同一事实；从重试参数或线程登录态取人会在恢复时变更申请人，均不采用。B端新增显式操作人服务重载，控制器从 SecurityUtils 取当前账户；旧受信内部签名保留兼容。退款人原始账户号与现有复核账户号比较，记录来源售后单；不扩展跨账号的自然人识别。

退款指令增加可选 requestedBy。原构造/准备签名兼容，历史缺日志或仅有商户号仍记 AFTER_SALE:单号，不能当真实人员。系统事件使用 SYSTEM:售后号；同一售后新日志不改变首个决策。支付域不读取交易域表，由交易域解析并通过SPI传递。无需迁移、不改外部HTTP请求体；本批不做历史回填、线下凭证、规则管理或旧接口退役。

## §2 实施计划

- [x] 新增真实商家入口与普通退款场景：登录账户入日志/退款单/复核单，自复核被拒；先确认红灯。
- [x] 新增 RefundRequesterService 接口/实现，解析原决策与系统/历史兼容，补单测和重放断言。
- [x] B端入口显式传实际操作人，售后服务重载保留原事务与门店权限；补偿新售后日志标明独立聚合。
- [x] 扩展 RefundRequest、prepareRefund 内部契约；普通/行业/积分现金按原日志带人，pay_refund/pay_approval使用真实申请人。
- [x] 跑普通/积分退款、复核、远程协议、架构守卫及模块全测；消融传递接线；独立审查；记录真实结果并提交。

## §5 验证记录

| AC | 测试与实测 |
|---|---|
| A1/A3 | RefundRequesterIdentityFlowTest.merchantLoginReachesDecisionRefundAndApprovalAndCannotReviewItself：四种真实B入口；首红 expected STAFF-ORIGINAL but MERCHANT；初始接线2场景绿 |
| A2/A3 | originalDecisionSurvivesRecoveryAndLaterDifferentOperators：首红 expected OPS-ORIGINAL but AFTER_SALE:单号；初始接线后通过；申请人不能参与两轮复核 |
| A3 积分 | pointsCashPreparationRetainsAuthenticatedApplicantDuringWorkerRecovery：真实积分现金准备、登录人、不同恢复worker，原现金指令与申请人保持 |
| A3 行业 | industryRefundUsesRefundDecisionMakerRatherThanOriginalOrderPlacer；PayInternalApiRoundTripTest.ordinaryRefundRoundTripPreservesFrozenOwnerCoordinatesAndUnknownOutcome 验证JSON/HTTP；RemoteOrdinaryRefundPortTest.echoLosingTheOriginalApplicantIsRejected 拒绝丢失身份的回执 |
| A4 | historicalMerchantNumberIsNotInventedAsAPerson、automaticAndBuyerDecisionsKeepTheirOwnSourceIdentity：历史商户号保持未知、系统来源与买家身份明确 |
| 来源隔离 | anotherTenantDecisionCannotSupplyTheApplicant：扩展首红取到FOREIGN-OPERATOR；解析器补充来源tenantNo约束，防止其他租户日志成为申请人 |
| A5 | AfterSaleRefundExecutionTest 两条补偿重试场景新增售后聚合日志和OPS-1断言 |

日志：`/private/tmp/pay-requester-red.log`（2失败0错误）、`pay-requester-green-v2.log`（11项0失败0错误）、`pay-requester-expanded.log`（13项、仅跨租户1失败0错误）；扩大绿灯：27项0失败0错误（`pay-requester-expanded-green.log`）；此轮尚未包含随后新增的行业实库场景。切断申请人传递的消融：originalDecisionSurvivesRecoveryAndLaterDifferentOperators 1失败0错误（`pay-requester-ablation.log`），实现已恢复。

### 最终验证范围

Java 21、本地Maven缓存：

- `mvn -o -f backend/pom.xml -pl shop-core,pay/pay-svc -am test`：**2174项，0失败、0错误、104跳过，BUILD SUCCESS**，日志 `/private/tmp/pay-requester-final-modules.log`；含真实HTTP普通退款requestedBy传输测试。
- 25类shop-app首轮集成：261项、0失败、1错误（行业新夹具只写source_system，违反现有来源请求键/哈希成组约束），日志 `/private/tmp/pay-requester-final-integration.log`。其余24类250项通过；申请人类11项中仅此夹具构造出错，尚未执行该行业路径。
- 补齐该夹具的来源请求键和哈希，生产代码未再改动；重新运行完整 `RefundRequesterIdentityFlowTest`：**11项、0失败、0错误、0跳过，BUILD SUCCESS**，包含行业资金端口实际路径，日志 `/private/tmp/pay-requester-industry-fixture-green.log`。
- 最终集成按测试类合并：**25类261项全部通过**（前次24类250项 + 修正夹具后11项）。这里不宣称首次261项所在Maven调用BUILD SUCCESS。
- 生成文档25个生成器产物最新；SQL无新增方言依赖；独立审查无阻塞项。无迁移，无真实商户通道联调或生产部署，未声称整仓后端全测。

复现集成命令（同一JAVA_HOME）：

```sh
mvn -o -f backend/pom.xml -pl shop-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RefundRequesterIdentityFlowTest,RemoteOrdinaryRefundPortTest,AfterSaleRefundExecutionTest,LegacyRefundGovernanceFlowTest,RefundReviewFlowTest,RefundOrderFlowTest,BasicRefundRecoveryFlowTest,PointsOrderRefundStageTest,PointsRefundPreparationTest,PointsSettlementRefundPathsTest,RefundDispatchBoundaryTest,StrictRefundCallbackFlowTest,RefundCoordinateRecoveryFlowTest,RefundOriginalExecutionRecoveryFlowTest,M5AfterSaleFlowTest,M7SettleFlowTest,BizEndpointPermTest,ArchitectureTest,PayReverseDependencyBudgetTest,PayAuditTrailTest,PayHasNoControllerTest,SchemaDriftTest,BasicReturnRefundFlowTest,RefundResourceRecoveryFlowTest,LineRefundFlowTest
```

## §6 实现对账

| 文件 | 与§2的实现对应 |
|---|---|
| RefundRequesterService / impl | 同租户、同售后、同子单的首次REFUNDING日志；系统与历史来源处理 |
| BizAfterSaleController / AfterSaleService / impl | 登录账户显式传入原事务决策；保留受信旧签名；补偿新售后日志锚点；积分现金从原日志取申请人 |
| RefundFinancialOwnerServiceImpl | 普通与行业现金请求/查询带原申请人，支付域不读取交易域表 |
| RefundRequest / SettlePort | 可选申请人与现金准备重载；旧构造与受信历史调用兼容 |
| SettlePortImpl / SettleService / impl / PaymentLedgerService / impl | 实际覆盖携带身份的新准备方法，保持原现金号、容量、事务边界 |
| RefundServiceImpl | 新源使用实际申请人，pay_refund/pay_approval同人隔离复用已有门禁；历史仍AFTER_SALE标记 |
| RefundRequesterIdentityFlowTest / AfterSaleRefundExecutionTest | 真实入口、历史/系统/跨租户、积分恢复、补偿新申请验证 |
| RemoteOrdinaryRefundPortTest / PayInternalApiRoundTripTest | 原申请人完整传输与缺失身份回执拒绝 |

偏差：人员事实复用原状态日志，未新增迁移或改写旧数据。历史没有实际人员的请求仍不能证明人员隔离，不声称已完成历史回填。远程双方需同版本，完整回执比较会拒绝丢失requestedBy的回包；本批不执行生产部署。跨租户测试额外发现缺过滤，已补来源租户约束。

独立审查：原决策来源、B端四分支、门店权限、事务边界、重载实际覆盖与双轮自复核门禁均核对，无阻塞项。补偿重试原申请人与新申请分开留痕，旧退款号和现金恢复证据未改写。

暂存实现快照（补本段前的 `git diff --cached --stat`）：

```text
 .../java/ai/neargo/shop/pay/SettleService.java     |   6 +
 .../acquiring/service/impl/RefundServiceImpl.java  |   6 +-
 .../ai/neargo/shop/pay/impl/SettleServiceImpl.java |   8 +-
 .../shop/pay/service/PaymentLedgerService.java     |   4 +
 .../pay/service/impl/PaymentLedgerServiceImpl.java |   9 +-
 .../shop/pay/svc/PayInternalApiRoundTripTest.java  |   2 +-
 .../neargo/shop/paybridge/port/SettlePortImpl.java |   6 +
 .../port/RemoteOrdinaryRefundPortTest.java         |  11 +-
 .../scenario/AfterSaleRefundExecutionTest.java     |   6 +
 .../scenario/RefundRequesterIdentityFlowTest.java  | 265 +++++++++++++++++++++
 .../ai/neargo/shop/spi/settle/RefundRequest.java   |  10 +-
 .../java/ai/neargo/shop/spi/settle/SettlePort.java |   6 +
 .../shop/trade/api/biz/BizAfterSaleController.java |  11 +-
 .../refund/service/RefundRequesterService.java     |   7 +
 .../impl/RefundFinancialOwnerServiceImpl.java      |  16 +-
 .../service/impl/RefundRequesterServiceImpl.java   |  49 ++++
 .../shop/trade/service/AfterSaleService.java       |  14 ++
 .../trade/service/impl/AfterSaleServiceImpl.java   |  70 +++++-
 docs/technical/README.md                           |   1 +
 docs/technical/TDD-支付服务-W3收单补完.md          |   2 +-
 docs/technical/TDD-支付服务-资金闭环收尾.md        |   2 +-
 docs/technical/TDD-支付服务-退款申请人贯通.md      |  78 ++++++
 docs/technical/TDD-支付服务-退款统一入口收尾.md    |   1 +
 docs/technical/reference/后端分层清单.md           |   4 +-
 .../technical/支付服务-完成度复核与剩余开发计划.md |   4 +-
 25 files changed, 566 insertions(+), 32 deletions(-)
```
