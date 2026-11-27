# 商城库初始化

在已选中的空商城库按下列脚本建表。脚本目录为 `backend/commerce/commerce-persistence/src/main/resources/db/mall`。每个子目录单独维护版本号。

| 顺序 | 文件 | 表数 |
|---|---|---:|
| 1 | `catalog/V1__mall_catalog.sql` | 9 |
| 2 | `order/V1__mall_order.sql` | 3 |
| 3 | `offline/V1__offline_payment.sql` | 2 |
| 4 | `fulfillment/V1__mall_fulfillment.sql` | 4 |
| 5 | `refund/V1__mall_refund.sql` | 2 |
| 6 | `subscription/V1__mall_subscription.sql` | 8 |
| 7 | `event/V1__mall_events.sql` | 2 |

共 28 张业务表和 2 张事务事件表。已按前六项建库的环境在启用线下核款前执行第 7 项。付款事实、订阅操作去重和权益变更通知与原订单共用商城事务。商城库与保存经营类型的基础能力库、保存门店授权的平台基础库分别初始化。
