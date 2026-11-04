# 台湾线下购买 API 状态

2026-11-04 更新：线下银行转账购买已完成接线，HTTP 契约以 [商城 HTTP](commerce-mall-http.md) 末节「线下银行转账购买」和 [OpenAPI](commerce-mall-openapi.json)（`task8-v1`）为准。本文件只记录实现边界与仍未覆盖的部分。

## 已实现

- **门面**：`OfflinePurchaseFacade` 覆盖报价、发出指引、凭证预留/上传/读取、申报、拒绝、核款、作废、退款出款确认、收款档案、待审队列与恢复扫描。
- **装配**：starter `CommerceOfflineAutoConfiguration`（`commerce.mall.offline.enabled`），缺 `OfflineCollectionPort`、`PrivateEvidencePort` 或 `profile-no` 时启动失败。
- **宿主资金**：`ShopMallOfflineCollectionAdapter` 对接 `PlatformCollectionService` / `PlatformRefundService`，唯一资金账本仍是 `stl_payment`。
- **私有凭证**：`ShopMallPrivateEvidenceAdapter` 写入既有媒体账（bizType `MALLPAY`，私有路径 `{entity}/{store}/mallpay/…`），`ShopMallEvidenceMediaRefs` 让回收扫描保留全部预留与已提交凭证。
- **权限**：V558 预置 8 个运营权限码；B 端路由在 `CommerceRoutePolicy` 精确放行。
- **恢复**：`commerce-mall-recovery` 任务追加线下动作扫描。

## 关键规则（已有测试）

- 申报不产生付款或权益；拒绝申报不释放占用。
- 只有运营核款写入实际到账；同一银行流水不能核两单；已付款订单再来的真实款记为 `MALL_UNMATCHED`。
- 账本明确拒绝的动作为 REJECTED 终态；未知保持 ACCEPTED 待恢复，单条失败不阻塞同单其他动作。
- 门店停用后运营仍可核款、作废、读凭证（按订单冻结的门店与主体校验）；商家操作需要门店正常营业。
- 代客订单的下单防重复按发起运营隔离。
- 线下原单退款只建待出款意图，不调网关；录入实际出款后才成功并交接商城完成权益调整。金额不符实收的退回不交接商城。
- 收款档案改版后，已下单未发出指引的订单仍按冻结版本发出（档案需仍启用）。

## 尚未覆盖

- 运营端页面（Task 5）与 B 端 Flutter 接入（Task 6）。
- 真实开发环境端到端联调（Task 7）；三家台湾线上通道仍未串接。
- 金额不符款的自动补差、拆分、多笔合并：按约定首期不做，人工处置。
- 部分出款（一次退款多笔转账）：首期不支持，出款金额须等于退款意图。
- 各分支迁移编号不一致（本分支 V558，`codex/ai-shop-codex` 已到 V622），合并时需统一重编号。
