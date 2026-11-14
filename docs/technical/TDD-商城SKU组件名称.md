# TDD-商城SKU组件名称

状态：已实现（2026-11-06，b端app 联调反馈「套餐组件没有名称，只能显示 软体 · 硬体 · 硬体」，用户确认加）
关联：[TDD-商城商品分类](./TDD-商城商品分类.md) · 接口真源 `docs/api/commerce-mall-openapi.json`（`SkuComponent.title`）

档位：1 —— commerce 表加一列（V12）、接口字段新增（可选、向后兼容）。

## §0 对账一 · 需求 → 设计

| 需求 | 设计 |
|---|---|
| B 端能显示套餐里每个组件叫什么 | `SkuComponent.title`（可选，1~64 字），随 SKU 版本冻结，店面/运营接口原样返回 |
| 运营可填 | 运营端 SKU 表单每个组件一个「组件名称」输入；版本详情表加一列 |
| 166 现有「開店套餐」也要有名称 | 种子脚本：已发布版本只差组件名称时，起一个带名称的新版本并单独发布（不重发行业） |

为什么不由服务端推导：硬件组件的 `hardwareSkuNo` 是自由编码（商城不接库存，没有硬件实体可查名称）；
方案/端组件虽能推导，但同一套餐里混用两种来源会让运营看不懂显示从哪来。统一由运营填写，空着时客户端按种类兜底。

## §1 现状与影响面

`mall_sku_component` 没有名称列；`SkuComponent` 10 个字段。影响：commerce-api（记录加字段 + 旧构造器）、
core（保存时校验）、persistence（V12 + 读写）、OpenAPI、ops-web（类型、表单、mock）、TW 种子。
已落单的订单组件快照不带名称（本次不改订单）。

## §2 方案

- 迁移 `V12__commerce_sku_component_title.sql`：`ALTER TABLE mall_sku_component ADD COLUMN title VARCHAR(64) NULL`。
- `SkuComponent(…, activationPolicy, title)`；保留 10 参构造器（title=null），既有调用方不动。
- 校验：`title` 为 null 或非空白且 ≤64，否则 `INVALID_ARGUMENT`。已发布版本只读（改名称 = 起新版本）。
- 持久化：随组件行写入；草稿改名走既有「组件成员变更」路径（同组件号 UPDATE）。

## §5 对账三 · 实现 → 需求

| 需求 | 证据 |
|---|---|
| 字段保存与可选 | `SkuComponentTitleTest`（core 2 条）；`SkuComponentTitleMysqlTest`（往返 + 草稿改名） |
| 接口返回 | `MallOpenApiResponseTest` 店面响应含 `"title":"…"` 与 `"title":null`，OpenAPI 3.1 校验通过 |
| 迁移 | `CommerceSchemaMysqlTest` 列数 21；`CommerceMigrationIsolationTest` 到 v12；`MallFulfillmentMysqlTest` 旧库升级路径 |
| 运营可填 | ops-web mock 测试「SKU 组件名称」；表单输入 + 版本详情列 |
| 166 数据 | 种子 `titles_only` 分支；离线模拟确认只为 tw-starter-bundle 起新版本并单独发布 |
