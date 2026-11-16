# ADR-059 正式环境收款通知以 near-pay 签名的门店号定归属

状态：已接受（2026-11-16，用户决定 D1「正式环境要实现收款播报」，并确定流程：near-pay 收到渠道回调后按订单找到门店，再经 HTTP 通知 ai-shop，ai-shop 按门店号推给该店已登录的终端）
日期：2026-11-16
关联：[TDD-沙箱收款消息与SSE](../TDD-沙箱收款消息与SSE.md)「2026-11-16 增量」· near-pay `docs/technical/TDD-商户收款沙箱HTTP结果出口-2026-11-13.md` 同日增量 · [主链路评审](../../handoff/bapp-api-alignment-2026-11-16/flow-review.md) 缺陷 2

## 背景

收款播报（商户 App 收款后 3 秒内语音播报）的链路是：渠道回调 near-pay → near-pay 在结算事务里记一条收款结果 → 签名 HTTP 通知 ai-shop `/internal/payment-results/merchant-collections` → ai-shop 落回执、经 outbox 投影 → 按「人 + 门店」的 SSE 流推给 App。

上线时只开了沙箱，两端都把环境写死为 `SANDBOX`（near-pay `MerchantCollectionNotificationProperties`、`MerchantCollectionResultRecorder`、`MerchantCollectionDeliveryService`；ai-shop `PaymentCollectionProperties`、`PaymentCollectionSecurity`、`PaymentCollectionServiceImpl`）。门店归属靠 ai-shop 的 `payment_collection_source`：每一个 `(environment, pay_app_id, source_type, source_id, payment_store_id)` 由运维配置一行映射到 `store_no`，查不到就 403。

这在沙箱可行（一家测试店），在正式环境不可行：

- 正式门店成百上千，开通收款后要逐店再配一行映射，漏配的店就听不到播报，且没有任何报错给商户。
- 通知里只有 near-pay 的内部门店 id（`storeId`），ai-shop 不认识它；ai-shop 自己也没有「near-pay 门店 id → 本店」的权威映射。
- 而 near-pay 本来就知道答案：宿主建收款单时按 `storeNo` 调它（`/internal/pay/v1/biz/stores/{storeNo}/…`），它用 `PayMerchantBindingService#storeById` 能把订单的 `storeId` 解析回宿主 `storeNo`。

## 决策

1. **门店归属以 near-pay 解析并签名的 `storeNo` 为准。** near-pay 在收款结果快照末尾加 `storeNo`（由订单的 `storeId` 经 `PayMerchantBindingService#storeById` 解析），整包随 HMAC 签名发出。ai-shop 在验签通过后接受它，前提是：
   - 该门店存在、未删除，所属主体未删除；
   - 主体市场与快照 `market` 一致。

   任一不满足回 403，near-pay 按既有退避重试并告警。
2. **显式绑定保留，且优先。** `payment_collection_source` 命中时以绑定为准，并要求与快照 `storeNo` 一致，不一致回 403（两边口径冲突不猜）。配置项 `source-bindings` 仍只允许 SANDBOX：正式环境不靠逐店配置，绑定只作为测试与人工覆盖手段。
3. **按环境分钥，不共享密钥。** 两端都改为「接受的环境列表 + 每个环境一把 ≥32 字符的签名密钥」，只允许 SANDBOX 与 PRODUCTION。签名串含环境名，验签时按请求头的环境取密钥；不在列表内的环境、或用沙箱密钥签的正式通知，一律 401。旧的单环境配置（`environment` + `secret`）保持原义，即只接收 SANDBOX。
4. **解析不到门店不阻塞收款。** near-pay 解析不到 `storeNo` 时照旧发不带该字段的快照并告警；ai-shop 对这种快照按显式绑定处理，找不到就拒收。收款结算本身不受影响。
5. **推送范围不变。** 门店确定后，仍推给该门店所属主体中在职、且对该店有 `PAY_ORDER_VIEW` 的店主与员工会话；每台已登录设备按「人 + 门店」各一条流。
6. **向后兼容。**
   - `storeNo` 在 ai-shop 端为可选字段。
   - near-pay 对改动前已记录的事件，重放时与旧快照比对，不因新增字段报冲突。原实现会在结算事务里抛出冲突，导致重复回调处理失败。
   - SSE 流的 scope 前缀沿用历史常量，已发出的游标继续有效。
   - 上线顺序：先 ai-shop，后 near-pay，最后两端同时加入 PRODUCTION 与其密钥。每一步停住时行为都与上线前一致。

## 备选与否决理由

| 备选 | 否决理由 |
|---|---|
| 维持逐店显式绑定，正式环境由运维配置 | 门店量下不可维护；漏配无报错，商户只会觉得「播报坏了」 |
| 门店开通收款时由宿主自动写绑定 | 宿主拿不到 near-pay 的门店 id，要么新增一次回传，要么改造开通流程；对存量门店还要补跑一次。复杂度高于收益 |
| ai-shop 反查 near-pay 门店（收到通知后再调一次 near-pay） | 多一次跨服务调用与失败面；信任的仍是 near-pay 的同一份数据，安全上没有增益 |
| 两环境共用一把密钥 | 沙箱配置、日志与测试机的暴露面远大于正式环境；共用即意味着拿到沙箱密钥就能伪造正式收款通知 |

## 后果

- **正面**
  - 正式环境的商户开通收款后即可听到播报，无需逐店配置。
  - 门店号由 near-pay 从订单解析并整包签名，App 与收款请求方都改不了它。
  - 沙箱密钥泄露不影响正式环境。
- **负面 / 风险**
  - **信任边界扩大**：ai-shop 现在相信 near-pay 对「哪家店」的判断。near-pay 一旦解析错（门店映射数据错误），收款通知会推给别的门店的店主。缓解：
    - ai-shop 仍校验门店存在与市场一致，只拦得住明显错误，拦不住同市场的错店；
    - 显式绑定可以覆盖；
    - near-pay 的门店映射是其收款与结算本身依赖的数据。映射错了，钱已经记错了店，播报只是同一错误的表现，不会新增资金风险。
  - **只影响通知，不影响资金**：播报与 App 收款记录展示都不改变任何资金状态，资金以 near-pay 与渠道对账为准。
  - **运维新增一把正式环境密钥**：要在两端同时配置、轮换时同时换。轮换期间可短暂让新旧两把并存，当前实现每个环境只认一把，轮换须两端同步切换。
  - near-pay 在 PRODUCTION 执行环境下读订单与门店映射的环境隔离行为，在本地未能验证，须在 166 联调时确认（交接文档 5.6）。
