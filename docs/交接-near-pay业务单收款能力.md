# 交接：near-pay 业务单收款能力（bizNo + order-receipts）

日期：2026-11-10 · 读者：near-pay 维护者、发版人、ai-shop 后端、商户 App（Flutter）
关联：ai-shop [TDD-业务单收款汇总](./technical/TDD-业务单收款汇总.md)、[TDD-美业订金线下记账](./technical/TDD-美业订金线下记账.md)、[B端门店功能矩阵-执行方案](./technical/B端门店功能矩阵-执行方案-2026-11-09.md) D5 / D16 / W1.3

## 一、一句话

near-pay 新增两项能力：**付款链接可以带业务单号**，以及**按业务单号读付款链接与储值扣款原始行的内部读口**。ai-shop 的「业务单收款汇总」靠它把扫码付款、储值扣款与外部记账加在一起，判断一张结算单 / 工单是否收齐。

代码已合进 near-pay `develop/s1`，**生产 nearpay-prod 还没有**（见第五节）。

## 二、新增能力（契约）

两个都是 ai-shop 宿主调用的内部接口，前缀 `/internal/pay/v1/biz/stores/{storeNo}`，经内部入口守卫；门店是宿主按商家授权校验过的当前门店，每次调用带操作人 `operatorNo`。

### 1. 代客收款带业务单号

```
POST /internal/pay/v1/biz/stores/{storeNo}/collections
{"operatorNo":"MCH…","amountMinor":30000,"subject":"结账","expireMinutes":30,"bizNo":"FST…"}
```

- `bizNo` 可选，字符集 `[A-Za-z0-9_.:-]{1,64}`，不合法 → `BAD_REQUEST`，不建单。
- 带了：写进 `pay_order.biz_no`（`biz_type` 仍是 `BIZ_COLLECT`）。`out_order_no` 仍是随机 `BZC…`，幂等不变。同一业务单可以开多张链接（拆单收款）。
- 不带：与改动前完全一样，`biz_no` 写随机外部单号。
- `CollectReq` 标了 `@JsonInclude(NON_NULL)`：不带 `bizNo` 时请求体与改动前一字不差，新旧版本互相兼容。

### 2. 按业务单号读原始行

```
GET /internal/pay/v1/biz/stores/{storeNo}/order-receipts?operatorNo=MCH…&bizNo=FST…
→ PortResult<BizReceipts>
{
  "links": [ {"payOrderNo","status","currency","amountMinor","paidMinor","refundedMinor","createdAt","paidAt"} ],
  "storedValue": [ {"usageNo","status","currency","amountMinor","occurredAt"} ]
}
```

| 字段 | 口径 |
|---|---|
| `links` | 本店 `biz_type=BIZ_COLLECT` 且 `biz_no` 等于该单号的支付单，按创建时间正序，最多 50 条；判权 `READ_LEDGER` |
| `links[].status` | 与付款链接列表同一口径：`PAID`（含部分退、全退）/ `PENDING` / `EXPIRED` / `VOIDED` |
| `links[].paidMinor / refundedMinor` | 支付单实收与已退；宿主按 `paid − refunded` 计入已收 |
| `storedValue` | `ent_usage` 中本店、`asset_kind='STORED_VALUE'`、`source_order_no` 等于该单号的消费行，最多 50 条 |
| `storedValue[].status` | 原样：宿主只计 `COMMITTED`；储值消费目前没有冲回流程 |
| 时间 | epoch 毫秒 |

只读，不改任何单据。**加减不在 near-pay 做**：应收、已收、还差、币种异常由 ai-shop 宿主算（`ReceivableSummaryServiceImpl`）。

### 不需要迁移

`pay_order.biz_no / paid_minor / refunded_minor`、`ent_usage.source_order_no` 都是已有列。schema 仍是 V70。

## 三、提交与分支

### near-pay（codeup `p2027/nearpay`）

| 分支 | 提交 | 说明 |
|---|---|---|
| `develop/s1` | `543034020` | 能力本体，已快进合入；基于 `8ad6f90d1` |
| `feat/receipts-biz-no` | `543034020` | 同一提交的特性分支 |
| `release/biz-receipts-on-f848afebb` | `c08b282a5` | 生产版 `f848afebb` + 只拣入这一个提交（`-x` 标记来源）；相对生产无 SQL 变化 |

改动文件（10 个，+294 / −6）：

| 动作 | 路径（`backend/pay/`） |
|---|---|
| 修改 | `pay-domain/…/checkout/service/CheckoutService.java`、`…/impl/CheckoutServiceImpl.java`：`collect` 重载带 `bizNo` |
| 修改 | `pay-domain/…/acquiring/service/MerchantCollectLinkQueryService.java`、`…/impl/MerchantCollectLinkQueryServiceImpl.java`：`ofBizNo` + `BizLink` |
| 修改 | `pay-domain/…/biz/PayBizFacade.java`：`collect` 重载、`receipts`、`BizReceipts`、`StoredValueReceipt`；储值映射器 setter 注入，构造函数不变 |
| 修改 | `pay-domain/…/client/PayBizInternalApi.java`：`CollectReq.bizNo`（`NON_NULL`）、`receipts` |
| 修改 | `pay-svc/…/svc/InternalPayBizEndpoint.java`：透传 `bizNo`、新增 `order-receipts` |
| 新增 | `pay-domain/src/test/…/checkout/CheckoutCollectBizNoTest.java`、`…/acquiring/CollectLinksByBizNoTest.java` |
| 修改 | `pay-svc/src/test/…/PayBizInternalApiRoundTripTest.java` |

### ai-shop（宿主侧，已合进 `codex/ai-shop-codex` 并上 166）

| PR | 内容 |
|---|---|
| [#9](https://github.com/robinwu2021-code/ai-shop/pull/9) | 收款汇总读口 `GET /biz/stores/{storeNo}/receivables/{bizNo}`、付款链接与外部记账带 `bizNo`、`backend/pay` 副本同样改动 |
| [#10](https://github.com/robinwu2021-code/ai-shop/pull/10) | `CollectReq.bizNo` 为空不序列化，宿主可先于 near-pay 发版 |

ai-shop 的 `backend/pay` 是 near-pay 迁出前的副本，**166 的收款服务以 near-pay 仓库为准**，两边记录形状保持一致（`RemoteBizPaymentAppService` 按同名字段反序列化）。

## 四、测试

| 测试 | 覆盖 |
|---|---|
| `CheckoutCollectBizNoTest`（3） | 带单号只写 `biz_no`、外部单号仍随机；不带单号与原来一样；单号非法不建单 |
| `CollectLinksByBizNoTest`（2） | 状态映射（部分退款仍 PAID、未过期 PENDING、过期 EXPIRED）与实收 / 已退；单号超长 10400 |
| `PayBizInternalApiRoundTripTest`（5） | 不带单号时请求体不含 `bizNo`；`BizReceipts` 跨边界不丢字段；`collect` 带单号透传 |

全量：`develop/s1` 上 3667 跑 / 0 失败 / 349 跳过；发版分支上 3463 跑 / 0 失败 / 296 跳过。构建使用隔离仓库 `/private/tmp/near-pay-m1/repository` 的克隆（共享 `~/.m2` 中 ai-shop 的公共构件已与 near-pay 分叉，不能混用）。

## 五、上线状态与怎么带上

**现状（2026-11-10）**：166 的 nearpay-prod 是用户本人发的 LINE Pay 版本（jar `841cfcaf`）。只读核对：含 `LinePayChannel`，**不含** `PayBizFacade$BizReceipts`、`MerchantCollectLinkQueryService$BizLink`；迁移最高 V70。

**影响**：ai-shop 已上线的收款汇总在 166 上调 `order-receipts` 会失败（`GET /biz/stores/{storeNo}/receivables/{bizNo}` 回 10500）；外部记账一侧不受影响。现有代客收款不受影响（#10 保证请求体不变）。

**不要单独发 `c08b282a5`**：会把 LINE Pay 覆盖掉。下次发 LINE Pay 时一起带上：

- 构建基于 `develop/s1`：已包含 `543034020`，无需额外操作。但 `develop/s1` 还带着 **V71 DDL**（见第六节），发之前须先由 root 执行或临时授权。
- 构建不基于 `develop/s1`：`git cherry-pick -x 543034020`。它只改上表 10 个文件，在 `f848afebb` 上拣入无冲突。

**发版后验证**：
1. 启动日志「schema … matches」，健康检查用 `GET /checkout/v1/store-codes/taipei-breakfast/share-head` 返回 200（near-pay 没有 `/actuator/health`，`/h5/q` 也是 404）。
2. 店主代客收款（不带单号）照常出链接，作废。
3. 店主 `GET /biz/stores/{storeNo}/receivables/FST1?dueMinor=0&currency=TWD` 返回 code 0（空明细）。
4. 可选：带 `bizNo` 开一张链接、付掉，再读汇总，已收等于该笔金额。

回滚：`pay-svc.jar` 回 `.prev`。

## 六、`develop/s1` 相对生产多出的内容（发版人注意）

生产 `f848afebb` 到 `develop/s1`（`543034020`）之间共 13 个提交，除本能力外都是其他人的：

| 提交 | 内容 |
|---|---|
| `f8269cc17`、`9a51857f6`、`3ce0e4c77`、`d828327c8`、`b2b858bb8`、`8d74fd96b` | PiPay 接入（`9a51857f6` 带 **V71**：改 `pay_channel_transaction_context`，DROP / 重建 `ck_context_format`、`ck_context_authorization`，加 `network_qualification_id` 列与索引，全是 DDL） |
| `49c8f6e65` | 共享 H5 支付恢复、JKOPay 对账 |
| `845353e6a` | 渠道凭据盘点文档 |
| `20260fa63` | LINE Pay H5 运行时修复 |
| `e27bb4bef`、`7657b3528`、`8ad6f90d1` | PayBy 台湾账户与 H5 验收 |
| `543034020` | 本能力 |

near-pay 启动不跑 Flyway，只做 SchemaVersionCheck：未先执行 V71 会报 `V70 vs V71 BEHIND`。

## 七、备份分支（2026-11-10）

本机 near-pay 有 7 个分支的提交不在远端，已原样推到 `backup/` 前缀下（未合并、未改动已有分支）：`backup/deploy/ops-console-9c013eb`、`backup/develop/s1`（本地落后远端 13 个提交的那份）、`backup/feat/ops-order-logs-io`、`backup/feat/ops-order-logs-io-2`、`backup/feat/ops-order-logs-io-live`、`backup/fix/schema-check-java-migrations`、`backup/merge/applepay-trial`。推送时跳过了 pre-push（只做备份）；以后要合并仍须过闸门。

## 八、待办

| # | 事项 | 负责 |
|---|---|---|
| 1 | 下次发 LINE Pay（或任何 nearpay-prod）时带上 `543034020`，按第五节验证 | 发版人（用户） |
| 2 | 若基于 `develop/s1` 发版：先处理 V71 DDL | 发版人 + 166 root |
| 3 | `release/biz-receipts-on-f848afebb` 用不上后可删除 | near-pay 维护者 |
| 4 | App 结账接入：付款链接 / 外部记账带 `bizNo`，读收款汇总判断收齐；美业带 `relatedBizNo` 抵扣订金 | 商户 App |
