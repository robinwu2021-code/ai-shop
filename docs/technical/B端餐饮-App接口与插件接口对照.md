# B 端餐饮：App 接口与插件接口对照

日期：2026-11-09 · 依据：[B端门店功能矩阵-开发计划](./B端门店功能矩阵-开发计划-2026-11-09.md) 阶段 2.3（已定案：App 改调插件接口，后端不另做 `/biz/food/*`）

读者：Flutter（ai-shop-bapp）与后端。左列是 App 现在按 mock 写的 15 项建议契约（`/biz/food/*`，见 [商户App功能矩阵与API交接文档](../交接-商户App功能矩阵与API.md) §14），右列是后端餐饮插件已实现的接口（`/biz/x/food/*`，242 个商家接口）和外卖渠道插件（`/biz/x/commerce/*`）。

新模型门店访问这两组路由的门禁已在开发计划 E3 放开；判权由每个接口自己的 `@PreAuthorize` 负责（码与[门店功能矩阵](./TDD-B端门店功能矩阵与方案准入.md)里餐饮 5 行一致）。

## 一、模型差异（先读这一节）

App mock 把一张「点餐单」画成一个对象（`DiningOrder`）。插件按餐饮业务拆成几层，App 需要按下面的对应关系组装：

| App 概念 | 插件对象 | 说明 |
|---|---|---|
| 点餐单（堂食） | **台账 check**（`/checks`） | 开台、并台、转台、清台；一桌一个 check，可多人共享 |
| 加菜草稿 | **购物车 cart**（`/carts`） | 先改购物车，再提交为批次 |
| 已下单的一批菜 | **下单批次 batch**（`/batches`） | 每次「下单」产生一个批次，后厨按批次出品 |
| 菜的出品状态 | **出品任务 task**（`/kitchen/tasks`、`/tasks/{no}:*`） | 开始 / 出餐 / 上菜 / 催单 / 作废 / 重做 |
| 退菜 | **业务退回 business-return** + 后厨退回 | 退菜 ≠ 退款，与 App 文档的口径一致 |
| 结账 | **报价 quote → 确认** + 结算单 settlement | 金额以服务端报价为准，App 不自己算 |
| 外送单 | **渠道订单**（`/biz/x/commerce/orders`） | 接单 / 拒单在渠道插件 |

## 二、逐条对照

| # | App 建议契约 | 插件接口 | 缺口 / 要对齐的地方 |
|---|---|---|---|
| 14-01 | `GET /biz/food/orders` 点餐单列表 | `GET /stores/{storeNo}/checks`（堂食）；`GET /stores/{storeNo}/batches`（外带、自提批次）；`GET /biz/x/commerce/stores/{storeNo}/orders`（外送单列表）、`GET /biz/x/commerce/orders/{no}`（详情） | 插件没有「三类合一」的列表接口；App 分别读三处后合并展示 |
| 14-02 | `GET /biz/food/orders/{orderNo}` 详情 | `GET /checks/{checkNo}`、`GET /checks/{checkNo}/actions`（操作记录）、`GET /batches/{batchNo}` | App 的 `dishes[].state` 改读出品任务状态 |
| 14-03 | `POST …/dishes` 加菜 | `POST /stores/{storeNo}/carts` → `POST /carts/{cartNo}/lines/{sourceLineRef}` → `POST /stores/{storeNo}/quotes` → `POST /quotes/{quoteNo}:confirm` → `POST /stores/{storeNo}/batches` | 从「一次调用」变成「购物车 → 报价 → 下单」，写操作都要带 `Idempotency-Key` |
| 14-04 | `POST …/returns` 退菜 | `POST /batches/{batchNo}/business-returns`；后厨侧 `POST /returns/{returnNo}/kitchen` | 原因必填；作废 / 重做出品任务用 `POST /tasks/{taskNo}:void\|remake`（需 `biz:aftersale`） |
| 14-05 | `POST …/checkout` 结账 | `POST /stores/{storeNo}/quotes`、`POST /quotes/{quoteNo}:confirm`；`GET /settlements/{settlementNo}` | **缺口 1**：多种付款方式（QR / 卡 / 现金）拆单收款的写入口不在餐饮插件，需后端确认走收款域（`/biz/payment/collection` 生成付款链接、`/biz/payment/offline-records` 记现金）还是补一个餐饮结账编排接口 |
| 14-06 | `POST …/accept\|reject` 外送接单 / 拒单 | `POST /biz/x/commerce/orders/{channelOrderNo}:accept\|reject` | 备餐分钟数 `prepMinutes` 是否随 accept 提交，需对照渠道插件请求体 |
| 14-07 | `GET /biz/food/summary` 首页汇总 | `GET /operating-reports/realtime`、`GET /stores/{storeNo}/checks`、`GET /stores/{storeNo}/kitchen/tasks` | **缺口 2**：没有单一汇总接口；短期 App 组合三个读口，长期视首页性能决定是否加汇总接口 |
| 14-08 | `GET /biz/food/menu` 菜单 | `GET /stores/{storeNo}/catalog`（门店可售目录，已合并菜单、菜品、加料、限量）；编辑态用 `GET /stores/{storeNo}/menus`、`/dishes`、`/modifiers`、`/option-groups` | App 的 `options[]` 对应加料组 option-groups + 加料 modifiers |
| 14-09 | `POST /biz/food/menu/items` 新增 / 编辑菜品 | 菜品是商品的餐饮扩展：先 `/biz/goods` 建商品，再 `POST /stores/{storeNo}/dishes/{goodsNo}` 写餐饮属性；出品站 `POST /stores/{storeNo}/sku-routes/{skuNo}` | 照片走商品图片；`station` 改为出品路由 |
| 14-10 | `POST …/items/{id}/status` 沽清 / 恢复 / 下架 | 沽清、恢复：`POST /stores/{storeNo}/sales-restrictions`；下架：菜单 `POST /menus/{menuNo}:disable` 或商品下架 | App 的三态拆成「限售」与「上下架」两件事 |
| 14-11 | `POST …/items/{id}/remaining` 今日剩余份数 | `POST /stores/{storeNo}/sales-restrictions`（每日限量，按门店营业日截止自动恢复） | `null` 取消 = 清除限售 |
| 14-12 | 桌台增删改、桌码 | `GET/POST /stores/{storeNo}/tables`、`POST /tables/{tableNo}`、`POST /tables/{tableNo}:archive`（删除）、`:set-status`；区域 `/table-areas`；桌码 `POST /tables/{tableNo}:rotate-token\|revoke-token` | 删除改为归档；`area` 是区域编号不是名称 |
| 14-13 | `GET /biz/food/kitchen`、`start\|done` | `GET /stores/{storeNo}/kitchen/tasks`、`POST /tasks/{taskNo}:start\|ready\|serve`、`:urge`；推送 `POST /kitchen-subscriptions` + `GET /kitchen-events` | App 的 `done` = 插件 `ready`；催菜标记读任务字段 |
| 14-14 | `GET/POST /biz/food/platforms` 外送平台 | `GET /biz/x/commerce/stores/{storeNo}/connections`（已连接平台）、`GET …/stores/{storeNo}/statistics/providers`（按平台统计）、`GET/POST /biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}`（接单策略，含暂停） | 暂停 / 恢复接单对应接单策略（缺口 3）；今日单量读按平台统计 |
| 14-15 | `GET /biz/food/report` 报表 | `GET /operating-reports/{daily\|period\|trend\|rankings\|…}`、`GET /operating-reports/metrics`；营业日报 `GET /reports/business-day` | 时段图、热销、翻台率逐项对照指标名 |

## 三、缺口清单（后端待办）

| # | 缺口 | 建议 |
|---|---|---|
| 1 | 结账多付款方式编排 | 与收款域对齐：二维码 / 刷卡走付款链接或感应收款，现金走外部记账；餐饮侧只做报价确认与结算单 |
| 2 | 首页汇总 | 短期 App 组合读；长期视性能补 `GET /stores/{storeNo}/summary` |
| 3 | 暂停 / 恢复接单 | 对照 `channel-order-policies` 的字段，确认「暂停」是否已有对应开关 |

## 四、共通约定（App 改造时统一）

- 金额：插件统一 `{currency, minor}`，`minor` 为整数最小单位。
- 枚举：一律大写机器码，不再用 Dart 枚举名（`dineIn` → 以插件返回为准）。
- 写操作：带 `Idempotency-Key`；带版本号的对象（菜单、桌台、配置）提交 `expectedVersion` / `version`。
- 门店：路径里的 `storeNo` 必须等于请求头 `X-Store-No`。
- App 改完后从 `pendingContracts` 删除 `^/biz/food/`。
