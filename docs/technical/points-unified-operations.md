# 统一积分操作接入说明（第二阶段）

入口为 `shop-base` 的 `PointsOperationsPort`，实现在支付域 `PointsOperationsPortImpl`。该接口仅供可信服务端适配器使用，不是对前端开放的 HTTP 接口。业务属主、权限及来源真实性由调用适配器验证；事实摘要只能校验内容完整性，不能代替支付来源认证。

第一阶段 `PointsEarningRulePort` 提供配置、匹配和计算。本接口负责支付侧实际受理及记账。第二阶段未切换任何订单接线，也未增加营销赠送来源或 B/C 接口。

## 标识与幂等

```java
var operation = new PointsOperationsPort.Operation(
        PointsOperationsPort.Source.TRADE,
        orderNo,              // 发放用真实子单号，预占用订单号，退款用售后号
        immutableOperationNo, // 同一次业务操作持久化后固定不变
        typeCode);
var context = new PointsOperationsPort.Context(requestId, operation, "CNY");
```

- 业务编号首版最长 40 字符，积分类型和操作号最长 64，requestId 最长 100。
- requestId 标识一次可重试请求；operation 标识业务承诺。两者均持久化在原 `pts_inbox` 元数据中，不建立第二套账本或队列。
- 重复请求/业务操作返回原回执；改内容重用任一标识均冲突。更换 requestId 不能产生第二笔发放。
- 不同 Action 使用不同请求号。释放预占必须使用原 HOLD 的 operation，不能新造一个 operation 来释放。
- 事实 eventNo 沿用原事实接口的全局唯一约定，调用适配器应包含积分类型及来源版本；同一编号不能承载不同类型或不同事实内容。
- 新凭证 `GRANT:V2:<类型化赠送持仓号>`、新预占根 `H2<hash>` 与旧凭证隔离。真实订单/子单号和支付快照不替换为伪造别名。
- 同类型同子单的底层持仓仍唯一；不得通过不同操作号或旧/新接口重复发放、预占。遇旧承诺应沿原接线处理；业务迁移在第三阶段执行。

## 余额与账户控制

`balance(typeCode, Holder.USER/STORE, holderNo)` 返回账户是否存在、PENDING、AVAILABLE、HELD、冻结标记、到期时间、版本及币种。不存在账户返回零余额并且不创建账户。PENDING/HELD/退款预留均不作为可消费余额。

`controlAccount(context, AccountControl(...))` 保留账户版本校验、操作人、备注和原冻结审计。冻结/解冻是账户控制，不移动 HELD，不替代抵扣预占或预占释放。冻结和解冻使用不同 operationNo。

## 赠送受理与状态

`submitEarning(context, Earning(command, paidFact))` 接收逐行规则快照及完整可信已支付事实。类型、子单、用户、版本、支付快照、数量与商品现金金额必须一致；交易必须 ONLINE，支付路径、账户版本、商户/门店、支付交易号及商户单号不可缺失。接口不接受任意最终积分数，也不伪造营销订单。

新受理校验 ACTIVE、配置开关（含 EARN 维度）、账户冻结/过期；重放原受理和已接受承诺的履约不因后来停用开关或进入 DRAINING 而被当成新承诺。

返回 ACCEPTED 仅表示已持久化受理，不能展示为已到账，也不表示已经预留预算。原事件队列在执行时检查预算，不透支；预算不足保留 RETRYABLE/MANUAL 处理链路，不写永久跳过凭证。实际发放按支付侧精度计算，PENDING 与完成后 AVAILABLE 沿原生命周期转换。

`status(operation, Action.EARN)` 查询当前事件处理状态、失败原因及 PENDING/AVAILABLE/SKIPPED 领域状态。其他异步事实同样查询各自 Action。原请求重放返回原受理回执，查询当前进度应使用 status。

## 抵扣与核销

- `quote(typeCode, currency, query)` 显式选择类型，报价不记账。
- `hold(context, command)` 在真实支付事务内复核报价、接受条款、余额及资金储备，AVAILABLE 转 HELD。
- `release(context, command)` 仅释放原未支付预占；已消费子持仓拒绝释放。
- `acceptLifecycle(context, paid/cancelled/finalizedFact)` 仍走原可信事实队列。核销由实际支付事实驱动，没有“传积分数就扣账”的旁路。
- `status(operation, HOLD)` 从根及子持仓读取 HELD、CONSUMED、PARTIALLY_CONSUMED、RELEASED、PARTIALLY_RELEASED 等状态，取消事实释放子持仓后不会沿用旧根 HELD 文案。

## 退款及追回

`prepareRefund`、`authorizeRefund`、`confirmRefund`、`abortRefund`、`compensateRefund`、`refundStatus` 委托原准备/现金授权/确认/终止/补偿证据链。每次按类型、售后号和币种校验准备记录，保留累计退款与差额追回规则。准备成功不表示现金退款已完成，补偿准备成功不表示远端补偿已到账。

现金确认必须匹配原支付系统已核验退款流水；布尔值或业务文案不能代替证据。零现金完成事实和售后结束事实仍通过 `acceptZeroCash` / `acceptClosure` 接收；异步确认通过 `acceptRefundFact` 接收。相关事实 eventNo、准备编号和输入摘要沿用原凭证契约，不改写银行交易标识。

所有账本和预算变更仍归属原 `payTxManager` 与 Posting 内核；不把业务数据库和远端资金调用塞进一个新大事务。公共 SPI 不暴露 Mapper、Posting 或支付模型，不能直接编辑余额。
