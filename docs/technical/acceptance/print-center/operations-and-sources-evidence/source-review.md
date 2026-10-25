# 新增退款/充值打印来源独立审查

审查对象：`/private/tmp/ai-shop-print-compact-integration`，相对基线 `ba44c7f0c` 的未提交 backend 生产代码；按要求只读审查，不修改源码、不编译、不运行测试。审查包含 SOURCE_RECHARGE_READ / SOURCE_REFUND_READ 补丁。

## 最终结论

Spec：通过静态审查，当前无未决 Critical / Important 发现。退款仅接通具备可靠义务终态合同的路径；旧版现金退款、缺来源的收银以及证据/金额组成不支持的组合保持关闭或 BLOCKED，不应扩大宣称覆盖范围。

Quality：通过静态代码审查，当前无未决 Critical / Important 发现；构建及测试结果仍待 root 集中验证，本报告不等于测试通过。

## 已修复的重要发现

### [Important，已修复] 旧版现金退款在返券义务完成前冻结可打印事实

- 位置：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundResultServiceImpl.java:198-199`，新增 `receiptFacts.recordRefund(as,child,current)`。
- 触发：现金退款 `closureVersion != 2`，渠道确认成功；`complete()` 先将 `resourceStatus` 设为 PENDING（143行），随后直接 `finish()`（150行）。新增调用仅核对售后 REFUNDED 和执行 SUCCEEDED，因而可冻结 READY 退款凭证。
- 证据：真正旧版 `coupon.release(orderNo)` 位于 `RefundResourceServiceImpl.java:100-107`，由后续 `recoverSuccessful -> resources.recover` 在独立事务执行；该步骤允许失败并保持 PENDING。`RefundPreparationServiceImpl.java:65,162-164` 的 closureEnabled 默认 false，或无 obligations scope 时会生成 v1，因此路径可达。
- 影响：可能先生成“已确认退款”的可打印业务凭证，随后返券义务失败；不符合所有者全部义务结清后冻结要求。
- 最终修复：`RefundResultServiceImpl.finish` 新增钩子限定 `closureVersion == 2`；`BusinessPrintFactServiceImpl.recordRefund` 同时校验版本2。v1继续原金融成功和资源恢复流程，不生成该来源凭证。已只读复核最终源码；v2调用仅来自 `closePending` 中 `obligations.settled(no)` 为真后的 owner 事务。对应集成测试显式启用v2，避免用旧版终态伪证。

## 另一个已修复的发现

- summary controller 原来硬要求 SOURCE_ORDER_READ，导致只有充值账户读取权/售后权的专岗无法发现对应来源。实现者已改为 `PrintSourceDataScopes.hasAnySource`，summary 请求统计按 scene source scope 过滤、事实引用按 sourceType scope 过滤；静态复核已消除该候选。

## 已核对通过的静态设计

- PAY posting 使用独立 StoredValueReceiptConfirmed 事件，冻结本次实际 principal、bonus、afterPrincipal+afterBonus；不查询当前余额生成历史。原 StoredValueCredited record 与 hash 算法未改写。
- 充值事件由 PAY 原 outbox 提交后进入 CORE，校验 eventNo/type/aggregate，CORE 在事务内写不可变附属事实和现有 outbox；重放比较规范 owner hash，冲突拒绝覆盖。
- biz_print_fact 是不可变业务附属表，没有新 job/attempt 或第二套投递引擎；BusinessPrintFactReady 仍交给现有 PrintFacade.onFact。
- REFUND/RECHARGE sourceTypes 与 catalog 一致；新增两个独立 port 以 sourceType 注册，cashier 无真实 POS 结算所有者故保持关闭。
- references、resolve、snapshot、scan 均显式 tenant/entity/store 限定，完整 reference 比较拒绝跨来源/版本/hash混用；保存支持64字符 entity/store/sourceNo。
- 充值 scope 由 SV_RECHARGE_READ 且 SV_ACCOUNT_READ 映射；退款 scope 沿真实 AFTERSALE 权限。生成、预览、模拟前置检查；compiler 将专属 scope 冻结进 PrintDocument。
- 历史 getDocument、reprint、simulation-of-job、export-create 读取 document/input requiredDataScopes；export 持久化该 scope，查询/下载再次检查，因此新来源文档不能只凭订单权读取。
- 版本化现金/积分退款钩子位于真实 owner 结清事务；现金 v2 由 obligations.settled 才 finish，积分由 confirmed outcome 与 COMMITTED execution、settled obligations 后才终结。
- 扩展 maintenance 从 registry 的 scenes/sourceTypes 扫描，按原 cursor 和现有 reconciliation 生成请求；summary 聚合所有 registered sources 并过滤权限。
- 缺 store 时 owner 钩子跳过新增凭证，未强迫缺门店历史业务因为打印失败而改变金融结果。
- 现金净额与退款商品/运费合计不一致、缺原支付证据等组合会保存 BLOCKED 事实，未伪造分摊；纯积分等缺原现金支付合同的组合不能宣称已具备可打印凭证。

## 验证边界

这是静态 spec + quality 审查。未运行构建或测试，不能据此宣称测试通过、真实出纸、生产启用完成。建议集中验收覆盖旧版返券失败、v2义务 pending/settled、积分零现金、充值后续余额变化和重放、专岗summary入口/跨来源过滤、历史文档与导出下载授权。

## 集中测试后最终小差异复核：充值三事件编号

root 集中测试发现此前新增 ReceiptConfirmed 错用既有 Received 的 `SR` 编号空间，真实首次充值因 publisher 的 immutable guard 拒绝而回滚。此前静态审查漏掉了这一跨生产者编号碰撞；本次已按 root 指定范围复核修复和真实三事件回归，不重新运行构建或测试。

- 最终生产代码 `StoredValuePostingServiceImpl.java:156` 仅对新事件使用 `no("SP", List.of("STORED_VALUE_RECEIPT_CONFIRMED", tenant, rechargeNo), json)`。新前缀与显式事件类型参与确定性摘要，和 `SR` Received / `SC` Credited 互不混用。
- 原 Received 编号仍为 `no("SR", List.of(tenant, rechargeNo), json)`；原 Credited 编号仍为 `no("SC", List.of(tenant, rechargeNo), json)`；原事件合同、旧 hash 算法没有因本次小差异变更。
- `PayEntitlementEventPublisher.java` 无差异；其 `insertIfAbsent` 后重新读取并核验 type/schema/aggregate/hash/occurredAt 的不可变防线保留，未以放宽 guard 掩盖编号冲突。
- `BusinessPrintSourceFlowTest.java:54-77` 调用真实 `confirmOffline` 生成充值及实际 outbox 行，读取并分别断言 Received(SR)、Credited(SC)、ReceiptConfirmed(SP) 的编号，验证新事件与两种旧事件不同。首次真实充值自身即可触发此前碰撞，因此该回归能覆盖故障触发点，而非仅构造三条假事件。该测试还涵盖第二次真实入账、原交易余额13000冻结/当前余额26000、重复消费与事务回滚断言。

本次小差异静态复核通过，无新增未决 Critical / Important；Spec/Quality 静态结论维持通过。该回归测试的运行结果由 root 集中验收记录，本审查没有运行 Maven。

## 商家入口联调后的退款别名小差异

商家入口审查发现原订单summary返回REFUND fact后，J03仍带原orderNo，原business resolver只接受afterSaleNo而拒绝真实订单入口。最终修复仅对REFUND在所有scope/type/factNo/revision/scene外层AND条件内增加括号包围的source_no/order_no/sub_order_no别名；充值仍sourceNo-only，snapshot完整ref/hash校验保持。真实refundflow补原订单/子单解析、无关单号/错误店/错误revision拒绝断言；已只读复核，无新增未决Critical / Important。完整商家入口审查见 `/tmp/print-next-wave/merchant-review.md`。未运行Maven。
