# SKU 与规格库 · V2 对齐页

状态：页 · 2026-08-28 · 第二梯队 #11。**结论：沿用不重建** —— 本页存在的目的就是防止 P2 有人重建四层。

| 事项 | 定论 |
|---|---|
| 规格四层（V195：项/值/类目绑定/商家覆盖） | **原样沿用**。V2 的 Option/OptionValue 是**领域视图**，物理承载就是 `spec_groups` 快照 + 四层库；不建 `prd_option` 表 |
| Option ↔ 规格的语义 | SALE 维度=Option（进 SKU 矩阵）；PROP=买家参数；**Modifier 永不进四层**（判据：影响成本或库存身份才是规格） |
| SKU 矩阵唯一性 | `uk(goods_no, market, option_values)` 前缀索引落地时验证长度；单规格恰一条（不变量 1，域层守卫） |
| 条码/货号 | `barcode`（EAN/UPC）与 `merchant_sku_code` 是 ERP/收银秤/供应商的通用键 —— **收银台扫码按 barcode 查 SKU**（B6 依赖，索引补 `idx_sku_barcode(entity_no, barcode)`） |
| 秤码（生鲜店内码） | 13 位店内码规则（7 前缀+5 重量/价）**记账不预建**；到位时解析层在收银台，不进商品表 |
| METERED 挂 SKU 级 | `prd_trait_metered.sku_no`：整鱼/切段标称重不同（已定，重申） |
| 时长档 | 「60/90 分钟」是 Option 产生两个 SKU + `prd_trait_service_variant` 覆盖时长（已定） |
| 五品类 `type` | P2 起为 `product_type` 的**缓存列**（非双源，长期保留，迁移手册 M15） |
