# 平台商城全局订单、核款队列与收退款统计

本批提供运营端只读汇总，页面位于平台商城的全局订单、核款队列、收入概况、收入明细。两套套餐保持独立购买、核款、退款和权益模型；新入口不改变原写权限。未部署。

## API
所有时间为 epoch 毫秒；响应沿用 ai-shop ApiResult 包装。

| GET 路径 | 新查询权限 | 查询内容 |
|---|---|---|
| /ops/platform-mall/orders | commerce:platform:orders:read | 两套套餐的全局购买订单 |
| /ops/platform-mall/claims | commerce:platform:claims:read | 有 SUBMITTED/REVIEWING 凭证的订单；主体套餐仅有 SUBMITTED 状态 |
| /ops/platform-mall/cash | commerce:platform:cash:read | 实际成功收款、退款的整段汇总及分页明细 |

共同参数：model（ENTITY/STORE，可选，省略取有权限模型）、entityNo、storeNo、currency（三位大写）、after（不透明游标）、limit（默认20，1–100）。
订单另支持 orderNo、status（各模型原状态）、createdFrom、createdTo，创建时间为左闭右开。资金接口必须提供 fromInclusive、toExclusive，为资金成功时间左闭右开，最长366天。

订单返回 items、nextCursor、includedModels、queriedAt；排序按创建时间倒序、ENTITY 在 STORE 前、同模型订单号倒序。items 包含 model/orderNo/entityNo/storeNo/currency/payableMinor/status/claimStatus/createdAt。payableMinor 是应付金额，不表示到账。

资金返回 summary、items、nextCursor、queriedAt、coverage。summary 按 model/currency/classification 分组，含 receivedMinor/refundedMinor/netMinor/receiptCount/refundCount。items 含 paymentNo/orderNo/originalPaymentNo/direction/currency/amountMinor/occurredAt/classification/model/entityNo/storeNo；按 occurredAt、paymentNo 倒序。所有金额均为最小货币单位十进制字符串，不允许转 Number 后累计；净额可以为负。

## 权限与归属
仅接受 MAIN 租户的 OPERATOR/OPS 身份。数据域必须明确，ALL 或 MERCHANT 主体范围在源 SQL 分页之前过滤；缺少数据域拒绝，其他维度不扩大到全域。

ENTITY 必须另有 merchant:merchant:read；STORE 必须另有 commerce:mall:order:read。核款队列还需 commerce:mall:offline:review，ENTITY 另需 merchant:merchant:ban。新读权限不替代任何原确认、拒绝、退款或代购权限。V670 新菜单默认只显式授权 SUPER_ADMIN，其他角色按正式 IAM 授权。

资金归属取源订单冻结的主体/门店，不取账本收款平台主体。账本由当前 ai-shop 内嵌 pay 提供；独立 near-pay 不承载 PLATFORM_MALL/MALL_UNMATCHED 账本。历史财务归属不随门店迁移转给新主体，原办理入口继续按当前门店与业务状态校验；删除或迁移后的历史订单可能只能查看全局索引，详情办理会明确拒绝，不扩大写权限。

## 统计口径与失败行为
仅统计 stl_payment 的已验证 SUCCESS PAY/REFUND，普通退款及异常款退款以同一支付账本资金单号计一次；不累计退款工作流、订单应付额、门店营业额或消费者支付总流水。

NORMAL、UNMATCHED、CLOSED_ORDER 分开；退款沿用原收款分类。CLOSED_ORDER 表示当前存在关闭标记，不能据此推断实际迟到时间。按 succeeded_at 的实际成功时间入窗口，退款按实际退款时间，不按核款操作时间或渠道观测时间。

资金账本整个请求使用只读 REPEATABLE_READ 快照，分批最多500行；总额覆盖整个查询窗口，不随明细翻页变化。源归属通过域内有界批查，避免跨库联表。coverage=CONSISTENT_PER_REQUEST 仅表示每次请求自身账本快照；跨请求翻页不是持久快照，期间补录/回填可能改变结果，刷新重置到第一页。

对可见订单的无效成功凭证、超额退款、归属冲突返回10409；全域且有两模型权限时未归属账本亦返回10409，避免虚假完整汇总。范围外记录不暴露诊断/数量。依赖缺失或异常返回失败，不回落为零收入；错误响应不携带凭证、银行流水或账户字段。

## 发布与回退
先保留已有工作区及核款页面，新四入口增量接线。V670 只新增4个 MENU 点和 SUPER_ADMIN 授权，不修改历史权限码、历史迁移或既有角色授权。暂不部署。回退先隐藏/撤销新4菜单授权，再回退新增页面/API；不要删除原套餐、资金账本或旧角色授权，不回写历史 Flyway 校验和。
