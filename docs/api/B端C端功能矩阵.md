# B 端与 C 端功能矩阵

状态：2026-11-19 整理。数据取自生产 v2 的网关索引（B 端 765 条、C 端 174 条入口）与线上的角色定义；管理范围的码表取自 `ManagementScopeServiceImpl`。
用途：按角色与功能点把入口理清，作为在库里**一次性配对**权限规则的依据。配对清单在最后一节。

## 一句话

- **B 端 765 条入口**：607 条按权限码判、152 条只要求登录、6 条留给入口判。入口注解上「只问是不是商家」的有 **123 条**——它们该不该带权限码、带哪个，是本文件要定的；其中 72 条已经在库里配了规则。
- **C 端 174 条入口**：C 端没有角色，只有「访客」与「登录的买家」。106 条已在库里配成要登录，68 条没配。
- 能从业务源码确认权限码的入口，本文件给出了建议的规则；确认不了的单独列出，不猜。

## 一、B 端

### 1.1 B 端的权限有三个来源

| 来源 | 判的是什么 | 码长什么样 | 谁来授 | 入口数 |
|---|---|---|---|---:|
| 门店角色 | 这个人在**当前门店**的角色有没有这个码 | `biz:goods`、`mch:pay:refund`、`pay:sv:plan:read` | 店主给店员分角色 | 535 |
| 管理范围 | 这个人在**某个管理空间**里，对某个品牌、门店、主体有没有这个码 | `sv.brand.activity.read`、`sv.settlement.read` | 管理空间的属主发授权 | 72 已配；另 10 条待确认 |
| 账户级 | 只要是登录的商家账号 | — | — | 139（入口上没有注解的 101 条，加上「只问是不是商家」里属于账户级的 38 条） |

另有 6 条留给入口判（带方法参数的门店成员检查、商城入口职责），83 条在门店准入表里对新模型门店关闭（见 [关闭入口清点](B端门店准入-关闭入口清点.md)）。

### 1.2 门店角色 × 功能点

「功能点」就是门店角色的权限码。读、写两列是网关索引里要这个码的入口数（一条入口的权限式里出现这个码就算）。

| 功能点（权限码） | 说明 | 老板 | 店长 | 店员 | 理货员 | 配送员 | 客服 | 读 | 写 |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|---:|---:|
| `biz:aftersale` | 处理售后 | ✓ | ✓ |  |  |  | ✓ | 5 | 4 |
| `biz:booking:manage` | 创建、调整及取消预约 | ✓ | ✓ |  |  |  |  | 0 | 0 |
| `biz:booking:view` | 查看预约 | ✓ | ✓ | ✓ |  |  |  | 0 | 0 |
| `biz:campaign` | 营销活动与报价 | ✓ | ✓ |  |  |  |  | 20 | 20 |
| `biz:customer` | 顾客列表与经营数据 | ✓ | ✓ |  |  |  |  | 19 | 16 |
| `biz:finance` | 结算账单与收款进件 | ✓ |  |  |  |  |  | 32 | 16 |
| `biz:goods` | 建商品、改价、上下架 | ✓ | ✓ |  |  |  |  | 16 | 27 |
| `biz:order:view` | 看订单与金额 | ✓ | ✓ | ✓ |  | ✓ | ✓ | 14 | 4 |
| `biz:pricing:config:publish` | 发布门店结账规则 | ✓ |  |  |  |  |  | 0 | 5 |
| `biz:pricing:config:read` | 查看门店结账规则 | ✓ |  |  |  |  |  | 4 | 1 |
| `biz:pricing:config:update` | 编辑门店结账规则草稿 | ✓ |  |  |  |  |  | 0 | 3 |
| `biz:pricing:tax:read` | 查看商家税配置 | ✓ |  |  |  |  |  | 1 | 0 |
| `biz:pricing:tax:update` | （角色页上没有这一项） | ✓ |  |  |  |  |  | 0 | 2 |
| `biz:print:config` | 配置打印模板与路由 | ✓ | ✓ |  |  |  |  | 8 | 11 |
| `biz:print:device` | 管理打印设备 | ✓ | ✓ |  |  |  |  | 2 | 12 |
| `biz:print:export` | 导出打印PDF | ✓ | ✓ | ✓ |  |  |  | 2 | 2 |
| `biz:print:extra` | 加印小票 | ✓ | ✓ |  |  |  |  | 0 | 1 |
| `biz:print:read` | 查看打印任务 | ✓ | ✓ | ✓ |  |  |  | 15 | 0 |
| `biz:print:reprint` | 补打小票 | ✓ | ✓ | ✓ |  |  |  | 0 | 2 |
| `biz:print:resolve` | 核实打印异常 | ✓ | ✓ |  |  |  |  | 3 | 11 |
| `biz:print:simulate` | 模拟打印 | ✓ | ✓ | ✓ |  |  |  | 2 | 1 |
| `biz:print:submit` | 提交打印 | ✓ | ✓ | ✓ |  |  |  | 0 | 2 |
| `biz:receive` | 到货登记与分拣 | ✓ | ✓ | ✓ | ✓ |  |  | 3 | 3 |
| `biz:report:export` | 导出经营报表 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:review` | 回评价、差评申诉 | ✓ | ✓ |  |  |  | ✓ | 4 | 2 |
| `biz:service:task:approve` | 审核服务调整 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:service:task:close` | 关闭已结清服务工单 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:service:task:coordinate` | 协调服务任务 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:service:task:self` | 操作本人服务任务 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:service:task:view` | 查看门店服务任务 | ✓ |  |  |  |  |  | 0 | 0 |
| `biz:ship` | 发货与标记送达 | ✓ | ✓ | ✓ |  | ✓ |  | 5 | 8 |
| `biz:stock` | 看商品、改库存 | ✓ | ✓ | ✓ | ✓ |  |  | 30 | 27 |
| `biz:store` | 店铺装修与配送规则 | ✓ | ✓ |  |  |  |  | 15 | 14 |
| `biz:store:admin` | （角色页上没有这一项） | ✓ |  |  |  |  |  | 14 | 39 |
| `biz:verify` | 核销取货 | ✓ | ✓ | ✓ |  |  |  | 5 | 3 |
| `mch:pay:app-grant` | 授权第三方代本店收款或退款 | ✓ |  |  |  |  |  | 2 | 4 |
| `mch:pay:collect` | 代客收款（出动态收款码） | ✓ | ✓ |  |  |  |  | 0 | 4 |
| `mch:pay:offline:manage` | 更正与作废 App 外收款 | ✓ | ✓ |  |  |  |  | 0 | 2 |
| `mch:pay:offline:record` | 记录 App 外收款 | ✓ | ✓ | ✓ |  |  |  | 0 | 1 |
| `mch:pay:order:view` | 查看本店收款记录（列表与详情） | ✓ | ✓ | ✓ |  |  |  | 9 | 1 |
| `mch:pay:qr` | 生成与停用本店收款码 | ✓ |  |  |  |  |  | 2 | 4 |
| `mch:pay:refund` | 发起原路退款 | ✓ |  |  |  |  |  | 2 | 2 |
| `mch:pay:statement` | 看本店收款流水 | ✓ |  |  |  |  |  | 5 | 0 |
| `pay:cash:receive` | 确认线下实收 | ✓ |  |  |  |  |  | 0 | 4 |
| `pay:ent:issue` | 定向发放权益 | ✓ |  |  |  |  |  | 0 | 1 |
| `pay:ent:issue:manage` | 管理权益发行规则 | ✓ |  |  |  |  |  | 0 | 1 |
| `pay:ent:read` | 查看权益与核销记录 | ✓ |  |  |  |  |  | 4 | 0 |
| `pay:ent:template:publish` | 发布与停用权益模板 | ✓ |  |  |  |  |  | 0 | 2 |
| `pay:ent:template:read` | 查看权益模板 | ✓ |  |  |  |  |  | 1 | 0 |
| `pay:ent:template:write` | 编辑权益模板 | ✓ |  |  |  |  |  | 0 | 2 |
| `pay:ent:use` | 核销权益 | ✓ |  |  |  |  |  | 0 | 3 |
| `pay:sv:account:read` | 查看顾客储值可用余额 | ✓ |  |  |  |  |  | 12 | 1 |
| `pay:sv:consume:create` | 使用储值核销服务 | ✓ |  |  |  |  |  | 1 | 4 |
| `pay:sv:plan:manage` | 管理门店储值方案 | ✓ |  |  |  |  |  | 4 | 2 |
| `pay:sv:plan:read` | 查看门店储值方案 | ✓ |  |  |  |  |  | 4 | 0 |
| `pay:sv:recharge:create` | 办理与取消未实收储值 | ✓ |  |  |  |  |  | 9 | 15 |
| `pay:sv:recharge:read` | 查看储值办理进度 | ✓ |  |  |  |  |  | 3 | 0 |
| `pay:sv:recharge:receive` | 核验储值实收 | ✓ |  |  |  |  |  | 0 | 4 |
| `trade:checkout:prepare` | 准备服务购买报价 | ✓ |  |  |  |  |  | 2 | 4 |
| `trade:manual-service:confirm` | 人工确认服务收款 | ✓ |  |  |  |  |  | 0 | 3 |

有 2 个码出现在入口的权限式里、但不在可分配给角色的码表里：`biz:pricing:tax:update`、`biz:store:admin`。它们只有店主（全部权限）过得去，自定义角色勾不到。

有 8 个码在码表里、但生产上没有任何入口要它（多半是所属的功能没有部署）：`biz:booking:manage`、`biz:booking:view`、`biz:report:export`、`biz:service:task:approve`、`biz:service:task:close`、`biz:service:task:coordinate`、`biz:service:task:self`、`biz:service:task:view`。

### 1.3 每个功能点管哪些入口

<details><summary><code>biz:aftersale</code> 处理售后 —— 9 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/after-sale` | biz:aftersale |
| GET | `/biz/after-sale/by-order/{subOrderNo}` | biz:aftersale |
| GET | `/biz/after-sale/worklist` | biz:aftersale |
| GET | `/biz/after-sale/{afterSaleNo}` | biz:aftersale |
| POST | `/biz/after-sale/{afterSaleNo}/approve` | biz:aftersale |
| POST | `/biz/after-sale/{afterSaleNo}/confirm-refund` | biz:aftersale |
| POST | `/biz/after-sale/{afterSaleNo}/receive` | biz:aftersale |
| POST | `/biz/after-sale/{afterSaleNo}/reject` | biz:aftersale |
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |

</details>

<details><summary><code>biz:campaign</code> 营销活动与报价 —— 40 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/activities` | biz:campaign |
| POST | `/biz/activities` | biz:campaign |
| GET | `/biz/activities/{activityNo}` | biz:campaign |
| POST | `/biz/activities/{activityNo}/publish` | biz:campaign |
| PUT | `/biz/activities/{activityNo}/status` | biz:campaign |
| POST | `/biz/activity-conflicts` | biz:campaign |
| GET | `/biz/activity-presets` | biz:campaign |
| GET | `/biz/campaign` | biz:campaign |
| POST | `/biz/campaign` | biz:campaign |
| POST | `/biz/campaign/{campaignNo}/toggle` | biz:campaign |
| GET | `/biz/coupon-issues` | biz:campaign |
| GET | `/biz/coupons` | biz:campaign |
| POST | `/biz/coupons` | biz:campaign |
| GET | `/biz/coupons/{couponNo}` | biz:campaign |
| POST | `/biz/coupons/{couponNo}/issue` | biz:campaign |
| PUT | `/biz/coupons/{couponNo}/status` | biz:campaign |
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| GET | `/biz/group-request/pool` | biz:campaign |
| POST | `/biz/group-request/{requestNo}/quote` | biz:campaign |
| GET | `/biz/group/pickups` | biz:campaign |
| GET | `/biz/group/{groupNo}` | biz:campaign |
| POST | `/biz/group/{groupNo}/dissolve` | biz:campaign |
| GET | `/biz/groups` | biz:campaign |
| POST | `/biz/groups` | biz:campaign |
| GET | `/biz/marketing-summary` | biz:campaign |
| GET | `/biz/marketing/summary` | biz:campaign |
| POST | `/biz/member-reach/send` | biz:campaign |
| GET | `/biz/period` | biz:campaign |
| GET | `/biz/period/{periodNo}` | biz:campaign |
| POST | `/biz/period/{periodNo}/cutoff` | biz:campaign |
| POST | `/biz/period/{periodNo}/decision` | biz:campaign |
| GET | `/biz/period/{periodNo}/purchase-lines` | biz:campaign |
| GET | `/biz/platform-activity` | biz:campaign |
| GET | `/biz/platform-activity/{activityNo}` | biz:campaign |
| POST | `/biz/platform-activity/{activityNo}/enrollment` | biz:campaign |
| POST | `/biz/platform-activity/{activityNo}/withdraw` | biz:campaign |
| POST | `/biz/pricing-manual-adjustments` | biz:campaign |
| GET | `/biz/pricing-manual-adjustments/{no}` | biz:campaign |
| POST | `/biz/pricing-manual-adjustments/{no}:quote` | biz:campaign |
| POST | `/biz/quote/{quoteNo}/revise` | biz:campaign |

</details>

<details><summary><code>biz:customer</code> 顾客列表与经营数据 —— 35 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/cross-store/compare` | biz:customer |
| GET | `/biz/cross-store/overview` | biz:customer |
| GET | `/biz/customers` | biz:customer |
| GET | `/biz/dashboard/stats` | biz:customer |
| GET | `/biz/inventory/export` | biz:customer |
| GET | `/biz/inventory/report/monthly` | biz:customer |
| GET | `/biz/inventory/report/ranking` | biz:customer |
| POST | `/biz/member-code/resolve` | biz:customer |
| POST | `/biz/member-reach/plan` | biz:customer |
| GET | `/biz/member-reach/task` | biz:customer |
| GET | `/biz/member-reach/task/{taskNo}` | biz:customer |
| GET | `/biz/member-segments` | biz:customer |
| POST | `/biz/member-segments` | biz:customer |
| POST | `/biz/member-segments/preview` | biz:customer |
| GET | `/biz/member-segments/{segmentNo}` | biz:customer |
| POST | `/biz/member-segments/{segmentNo}/remove` | biz:customer |
| GET | `/biz/member-settings` | biz:customer |
| GET | `/biz/member-tags` | biz:customer |
| POST | `/biz/member-tags` | biz:customer |
| PUT | `/biz/member-tags/{tagNo}` | biz:customer |
| POST | `/biz/member-tags/{tagNo}/merge` | biz:customer |
| GET | `/biz/member-tags/{tagNo}/usage` | biz:customer |
| GET | `/biz/members` | biz:customer |
| POST | `/biz/members` | biz:customer |
| POST | `/biz/members/audience-preview` | biz:customer |
| GET | `/biz/members/stats` | biz:customer |
| POST | `/biz/members/tags` | biz:customer |
| POST | `/biz/members/tags/batch` | biz:customer |
| GET | `/biz/members/{memberNo}` | biz:customer |
| PUT | `/biz/members/{memberNo}` | biz:customer |
| GET | `/biz/stored-value/customer-candidates` | (biz:customer 且 pay:sv:recharge:create) |
| POST | `/biz/stored-value/customer-selections` | (biz:customer 且 pay:sv:recharge:create) |
| POST | `/biz/stores/{storeNo}/members` | biz:customer |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |

</details>

<details><summary><code>biz:finance</code> 结算账单与收款进件 —— 48 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/deposit` | biz:finance |
| GET | `/biz/deposit/txns` | biz:finance |
| GET | `/biz/merchant/debt` | biz:finance |
| POST | `/biz/merchant/onboarding` | biz:finance |
| GET | `/biz/merchant/pay-channel` | biz:finance |
| GET | `/biz/merchant/pay-service` | biz:finance |
| POST | `/biz/merchant/pay-service/apply` | biz:finance |
| GET | `/biz/merchant/payment` | biz:finance |
| POST | `/biz/merchant/payment` | biz:finance |
| POST | `/biz/merchant/payment/store/{storeNo}` | biz:finance |
| POST | `/biz/merchant/payment/{payChannel}/refresh` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims` | biz:finance |
| POST | `/biz/pay/v2/channel-account-claims` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims/by-request/{requestNo}` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/commands/{requestNo}` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | biz:finance |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | biz:finance |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files/{fileNo}` | biz:finance |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/submit` | biz:finance |
| GET | `/biz/payout-account` | biz:finance |
| POST | `/biz/payout-account` | biz:finance |
| GET | `/biz/points/store-account` | biz:finance |
| GET | `/biz/points/store-agreement` | biz:finance |
| POST | `/biz/points/store-agreement/sign` | biz:finance |
| POST | `/biz/points/store-agreement/terminate` | biz:finance |
| GET | `/biz/points/store-entries` | biz:finance |
| GET | `/biz/points/store-statement` | biz:finance |
| POST | `/biz/points/store-statement-confirm` | biz:finance |
| POST | `/biz/points/store-statement-dispute` | biz:finance |
| GET | `/biz/points/store-statement-lines` | biz:finance |
| GET | `/biz/points/store-statements` | biz:finance |
| GET | `/biz/points/store-terms` | biz:finance |
| GET | `/biz/points/store-types` | biz:finance |
| POST | `/biz/pricing-manual-adjustments/{no}:approve` | biz:finance |
| GET | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | biz:finance |
| PUT | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | biz:finance |
| GET | `/biz/settle/batch` | biz:finance |
| GET | `/biz/settle/bills` | biz:finance |
| GET | `/biz/settle/bills/{settleNo}` | biz:finance |
| GET | `/biz/settle/daily-flow` | biz:finance |
| GET | `/biz/settle/income` | biz:finance |
| GET | `/biz/settle/invoice-pending` | biz:finance |
| GET | `/biz/settle/invoice-title` | biz:finance |
| GET | `/biz/settle/invoices` | biz:finance |
| POST | `/biz/settle/invoices` | biz:finance |
| GET | `/biz/settle/rate-card` | biz:finance |
| GET | `/biz/settle/statement` | biz:finance |

</details>

<details><summary><code>biz:goods</code> 建商品、改价、上下架 —— 43 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/card-product-bindings` | biz:goods |
| GET | `/biz/card-product-presets` | biz:goods |
| POST | `/biz/goods/describe` | biz:goods |
| POST | `/biz/goods/parse-text` | biz:goods |
| POST | `/biz/goods/recognize` | biz:goods |
| POST | `/biz/goods/save` | biz:goods |
| POST | `/biz/goods/zip-import` | biz:goods |
| POST | `/biz/goods/zip-plan` | biz:goods |
| GET | `/biz/goods/{goodsNo}/draft` | biz:goods |
| POST | `/biz/goods/{goodsNo}/draft/discard` | biz:goods |
| PUT | `/biz/goods/{goodsNo}/inv-mode` | biz:goods |
| GET | `/biz/goods/{goodsNo}/pay-mode` | biz:goods |
| PUT | `/biz/goods/{goodsNo}/pay-mode` | biz:goods |
| POST | `/biz/goods/{goodsNo}/presale` | biz:goods |
| POST | `/biz/goods/{goodsNo}/publish` | biz:goods |
| GET | `/biz/goods/{goodsNo}/publish-preview` | biz:goods |
| GET | `/biz/goods/{goodsNo}/revisions` | biz:goods |
| GET | `/biz/goods/{goodsNo}/revisions/{revisionNo}` | biz:goods |
| POST | `/biz/goods/{goodsNo}/revisions/{revisionNo}/fork` | biz:goods |
| POST | `/biz/goods/{goodsNo}/store-price` | biz:goods |
| POST | `/biz/goods/{goodsNo}/submit` | biz:goods |
| POST | `/biz/goods/{goodsNo}/toggle` | biz:goods |
| PUT | `/biz/inventory/category-setting/{categoryNo}` | biz:goods |
| GET | `/biz/my-spec-dims` | biz:goods |
| POST | `/biz/my-spec-dims/{dimNo}/archive` | biz:goods |
| POST | `/biz/my-spec-dims/{dimNo}/rename` | biz:goods |
| GET | `/biz/pickable-props` | biz:goods |
| POST | `/biz/sku-identity/barcode` | biz:goods |
| GET | `/biz/sku-identity/export` | biz:goods |
| POST | `/biz/sku-identity/import` | biz:goods |
| POST | `/biz/sku-identity/import/plan` | biz:goods |
| GET | `/biz/spec-dims` | biz:goods |
| POST | `/biz/spec-dims` | biz:goods |
| GET | `/biz/spec-dims/{dimNo}/values` | biz:goods |
| POST | `/biz/spec-override/{categoryNo}` | biz:goods |
| GET | `/biz/spec-props` | biz:goods |
| GET | `/biz/spec-templates` | biz:goods |
| POST | `/biz/spec-templates` | biz:goods |
| POST | `/biz/spec-values` | biz:goods |
| GET | `/biz/spu-std` | biz:goods |
| GET | `/biz/store-sales-catalog` | biz:goods |
| POST | `/biz/store-sales-catalog/versions` | biz:goods |
| GET | `/biz/store-spec-dims` | biz:goods |

</details>

<details><summary><code>biz:order:view</code> 看订单与金额 —— 18 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/checkout/{operationNo}/offline-receipts/lookup` | biz:order:view |
| GET | `/biz/checkout/{operationNo}/offline-receipts/{paymentOperationNo}` | biz:order:view |
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| GET | `/biz/order` | biz:order:view |
| GET | `/biz/order/worklist` | biz:order:view |
| GET | `/biz/order/{orderNo}/points-earning` | biz:order:view |
| GET | `/biz/order/{subOrderNo}` | biz:order:view |
| GET | `/biz/order/{subOrderNo}/full` | biz:order:view |
| GET | `/biz/order/{subOrderNo}/inventory-trace` | biz:order:view |
| GET | `/biz/order/{subOrderNo}/logistics-updates` | biz:order:view |
| GET | `/biz/order/{subOrderNo}/trace` | biz:order:view |
| POST | `/biz/service-orders/counter-quotes` | (trade:checkout:prepare 且 biz:order:view) |
| GET | `/biz/service-orders/counter-quotes/{operationNo}` | (trade:checkout:prepare 且 biz:order:view) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| GET | `/biz/service-orders/counter-quotes/{operationNo}/state` | (trade:checkout:prepare 且 biz:order:view) |
| GET | `/biz/service-orders/{orderNo}/manual-admission` | biz:order:view |
| GET | `/biz/service-orders/{orderNo}/manual-source` | biz:order:view |

</details>

<details><summary><code>biz:pricing:config:publish</code> 发布门店结账规则 —— 5 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/pricing-configs/initialize` | biz:pricing:config:publish |
| POST | `/biz/pricing-configs/{no}/publish` | biz:pricing:config:publish |
| POST | `/biz/pricing-configs/{no}/rollback` | biz:pricing:config:publish |
| POST | `/biz/pricing/fee-rules/{ruleNo}` | biz:pricing:config:publish |
| POST | `/biz/pricing/fee-scopes/{scopeNo}/adjustments/{applicationNo}:apply` | biz:pricing:config:publish |

</details>

<details><summary><code>biz:pricing:config:read</code> 查看门店结账规则 —— 5 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pricing-configs/{no}/draft` | biz:pricing:config:read |
| POST | `/biz/pricing-configs/{no}/preview` | biz:pricing:config:read |
| GET | `/biz/pricing-configs/{no}/revisions/{revisionNo}` | biz:pricing:config:read |
| GET | `/biz/pricing/fee-rules/{ruleNo}` | biz:pricing:config:read |
| GET | `/biz/pricing/fee-scopes/{scopeNo}/adjustments` | biz:pricing:config:read |

</details>

<details><summary><code>biz:pricing:config:update</code> 编辑门店结账规则草稿 —— 3 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| PUT | `/biz/pricing-configs/{no}/draft` | biz:pricing:config:update |
| POST | `/biz/pricing-configs/{no}/reset` | biz:pricing:config:update |
| POST | `/biz/pricing-configs/{no}/upgrade` | biz:pricing:config:update |

</details>

<details><summary><code>biz:pricing:tax:read</code> 查看商家税配置 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pricing-tax-settings` | biz:pricing:tax:read |

</details>

<details><summary><code>biz:pricing:tax:update</code>  —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| PUT | `/biz/pricing-tax-settings` | biz:pricing:tax:update |
| POST | `/biz/pricing-tax-settings/reset` | biz:pricing:tax:update |

</details>

<details><summary><code>biz:print:config</code> 配置打印模板与路由 —— 19 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/print/route-versions/{versionNo}/dry-run` | biz:print:config |
| POST | `/biz/print/route-versions/{versionNo}/publish` | biz:print:config |
| GET | `/biz/print/routes` | biz:print:config |
| POST | `/biz/print/routes` | biz:print:config |
| GET | `/biz/print/routes/{routeNo}/versions` | biz:print:config |
| POST | `/biz/print/routes/{routeNo}/versions` | biz:print:config |
| GET | `/biz/print/scene-policies` | biz:print:config |
| PUT | `/biz/print/scene-policies/{scene}` | biz:print:config |
| GET | `/biz/print/scene-policies/{scene}/versions` | biz:print:config |
| GET | `/biz/print/scenes/{scene}/examples` | biz:print:config |
| GET | `/biz/print/scenes/{scene}/fields` | biz:print:config |
| POST | `/biz/print/template-resolutions/preview` | biz:print:config |
| PUT | `/biz/print/template-versions/{versionNo}` | biz:print:config |
| POST | `/biz/print/template-versions/{versionNo}/preview` | biz:print:config |
| POST | `/biz/print/template-versions/{versionNo}/publish` | biz:print:config |
| GET | `/biz/print/templates` | biz:print:config |
| POST | `/biz/print/templates` | biz:print:config |
| GET | `/biz/print/templates/{templateNo}/versions` | biz:print:config |
| POST | `/biz/print/templates/{templateNo}/versions` | biz:print:config |

</details>

<details><summary><code>biz:print:device</code> 管理打印设备 —— 14 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/print/agents` | biz:print:device |
| POST | `/biz/print/agents` | biz:print:device |
| GET | `/biz/print/agents/{agentNo}` | biz:print:device |
| POST | `/biz/print/agents/{agentNo}/credential-rotations` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/agents/{agentNo}/enrollments` | biz:print:device |
| POST | `/biz/print/agents/{agentNo}/revocations` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/printers` | biz:print:device |
| PUT | `/biz/print/printers/{printerNo}` | biz:print:device |
| POST | `/biz/print/printers/{printerNo}/connection-changes` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/printers/{printerNo}/credential-rotations` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/printers/{printerNo}/probes` | biz:print:device |
| POST | `/biz/print/printers/{printerNo}/status-changes` | biz:print:device |
| POST | `/biz/print/printers/{printerNo}/test-jobs` | biz:print:device |
| POST | `/biz/print/printers/{printerNo}/uncertainty-resolutions` | (biz:print:device 且 biz:print:resolve) |

</details>

<details><summary><code>biz:print:export</code> 导出打印PDF —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/print/exports` | biz:print:export |
| POST | `/biz/print/exports` | biz:print:export |
| GET | `/biz/print/exports/{exportNo}` | biz:print:export |
| POST | `/biz/print/exports/{exportNo}/download-grants` | biz:print:export |

</details>

<details><summary><code>biz:print:extra</code> 加印小票 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/print/jobs/{jobNo}/extra-copies` | biz:print:extra |

</details>

<details><summary><code>biz:print:read</code> 查看打印任务 —— 15 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/print/adapters` | biz:print:read |
| GET | `/biz/print/batches/{batchNo}` | biz:print:read |
| GET | `/biz/print/changes` | biz:print:read |
| GET | `/biz/print/health` | biz:print:read |
| GET | `/biz/print/jobs` | biz:print:read |
| GET | `/biz/print/jobs/{jobNo}` | biz:print:read |
| GET | `/biz/print/jobs/{jobNo}/attempts` | biz:print:read |
| GET | `/biz/print/jobs/{jobNo}/document` | biz:print:read |
| GET | `/biz/print/operations/{operationNo}` | biz:print:read |
| GET | `/biz/print/orders/{orderNo}/summary` | biz:print:read |
| GET | `/biz/print/printers` | biz:print:read |
| GET | `/biz/print/printers/{printerNo}` | biz:print:read |
| GET | `/biz/print/requests` | biz:print:read |
| GET | `/biz/print/requests/{requestNo}` | biz:print:read |
| GET | `/biz/print/scenes` | biz:print:read |

</details>

<details><summary><code>biz:print:reprint</code> 补打小票 —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/print/jobs/{jobNo}/reprints` | biz:print:reprint |
| POST | `/biz/print/reprints` | biz:print:reprint |

</details>

<details><summary><code>biz:print:resolve</code> 核实打印异常 —— 14 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/print/action-logs` | biz:print:resolve |
| POST | `/biz/print/agents/{agentNo}/credential-rotations` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/agents/{agentNo}/revocations` | (biz:print:device 且 biz:print:resolve) |
| GET | `/biz/print/incidents` | biz:print:resolve |
| POST | `/biz/print/incidents/{incidentNo}/acknowledgements` | biz:print:resolve |
| POST | `/biz/print/jobs/{jobNo}/manual-confirmations` | biz:print:resolve |
| GET | `/biz/print/jobs/{jobNo}/receipts` | biz:print:resolve |
| POST | `/biz/print/jobs/{jobNo}/result-queries` | biz:print:resolve |
| POST | `/biz/print/jobs/{jobNo}/retries` | biz:print:resolve |
| POST | `/biz/print/jobs/{jobNo}/stale-releases` | biz:print:resolve |
| POST | `/biz/print/printers/{printerNo}/connection-changes` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/printers/{printerNo}/credential-rotations` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/printers/{printerNo}/uncertainty-resolutions` | (biz:print:device 且 biz:print:resolve) |
| POST | `/biz/print/requests/{requestNo}/generation-retries` | biz:print:resolve |

</details>

<details><summary><code>biz:print:simulate</code> 模拟打印 —— 3 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/print/simulations` | biz:print:simulate |
| POST | `/biz/print/simulations` | biz:print:simulate |
| GET | `/biz/print/simulations/{simulationNo}` | biz:print:simulate |

</details>

<details><summary><code>biz:print:submit</code> 提交打印 —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/print/jobs/{jobNo}/cancellations` | biz:print:submit |
| POST | `/biz/print/requests` | biz:print:submit |

</details>

<details><summary><code>biz:receive</code> 到货登记与分拣 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| POST | `/biz/order/{subOrderNo}/confirm-offline-pay` | biz:receive |
| POST | `/biz/pickup/arrived` | biz:receive |
| GET | `/biz/pickup/picking` | biz:receive |
| POST | `/biz/pickup/{orderNo}/report` | biz:receive |
| GET | `/biz/stores/{storeNo}/pos/access` | biz:receive |

</details>

<details><summary><code>biz:review</code> 回评价、差评申诉 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| GET | `/biz/review` | biz:review |
| GET | `/biz/review/appeals` | biz:review |
| GET | `/biz/review/{reviewNo}` | biz:review |
| POST | `/biz/review/{reviewNo}/appeal` | biz:review |
| POST | `/biz/review/{reviewNo}/reply` | biz:review |

</details>

<details><summary><code>biz:ship</code> 发货与标记送达 —— 13 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| GET | `/biz/express/companies` | (biz:ship 或 biz:store) |
| POST | `/biz/order/{subOrderNo}/delivered` | biz:ship |
| GET | `/biz/order/{subOrderNo}/express` | biz:ship |
| POST | `/biz/order/{subOrderNo}/express` | biz:ship |
| POST | `/biz/order/{subOrderNo}/express/cancel` | biz:ship |
| GET | `/biz/order/{subOrderNo}/express/quotes` | biz:ship |
| POST | `/biz/order/{subOrderNo}/fulfillment-change` | biz:ship |
| GET | `/biz/order/{subOrderNo}/fulfillment-options` | biz:ship |
| POST | `/biz/order/{subOrderNo}/prepare` | biz:ship |
| POST | `/biz/order/{subOrderNo}/ship` | biz:ship |
| POST | `/biz/order/{subOrderNo}/ship/precheck` | biz:ship |
| POST | `/biz/order/{subOrderNo}/waybill` | biz:ship |

</details>

<details><summary><code>biz:stock</code> 看商品、改库存 —— 57 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/fulfillment/carriers` | biz:stock |
| GET | `/biz/goods` | biz:stock |
| GET | `/biz/goods/inv-mode` | biz:stock |
| GET | `/biz/goods/{goodsNo}` | biz:stock |
| GET | `/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` | biz:stock |
| PUT | `/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` | biz:stock |
| POST | `/biz/goods/{goodsNo}/stock` | biz:stock |
| POST | `/biz/goods/{goodsNo}/store-stock` | biz:stock |
| POST | `/biz/inventory/adjust` | biz:stock |
| GET | `/biz/inventory/balances` | biz:stock |
| GET | `/biz/inventory/category-setting` | biz:stock |
| POST | `/biz/inventory/counts` | biz:stock |
| GET | `/biz/inventory/counts/{no}` | biz:stock |
| PUT | `/biz/inventory/counts/{no}/lines` | biz:stock |
| POST | `/biz/inventory/counts/{no}/post` | biz:stock |
| POST | `/biz/inventory/counts/{no}/void` | biz:stock |
| GET | `/biz/inventory/cross-store` | biz:stock |
| GET | `/biz/inventory/documents` | biz:stock |
| GET | `/biz/inventory/food-stock-projections` | biz:stock |
| GET | `/biz/inventory/food-stock-projections/{sourceItemNo}` | biz:stock |
| POST | `/biz/inventory/inbounds` | biz:stock |
| PUT | `/biz/inventory/inbounds/{no}` | biz:stock |
| POST | `/biz/inventory/inbounds/{no}/post` | biz:stock |
| POST | `/biz/inventory/inbounds/{no}/void` | biz:stock |
| GET | `/biz/inventory/item-by-sku` | biz:stock |
| GET | `/biz/inventory/items/by-barcode` | biz:stock |
| GET | `/biz/inventory/items/{itemId}` | biz:stock |
| GET | `/biz/inventory/items/{item}/valuation` | biz:stock |
| POST | `/biz/inventory/items/{item}/valuation` | biz:stock |
| GET | `/biz/inventory/ledger` | biz:stock |
| GET | `/biz/inventory/locations` | biz:stock |
| POST | `/biz/inventory/outbounds` | biz:stock |
| POST | `/biz/inventory/outbounds/{no}/post` | biz:stock |
| POST | `/biz/inventory/outbounds/{no}/void` | biz:stock |
| GET | `/biz/inventory/pickable` | biz:stock |
| PUT | `/biz/inventory/safety-stock` | biz:stock |
| GET | `/biz/inventory/summary` | biz:stock |
| GET | `/biz/inventory/suppliers` | biz:stock |
| POST | `/biz/inventory/suppliers` | biz:stock |
| PUT | `/biz/inventory/suppliers/{no}` | biz:stock |
| POST | `/biz/inventory/suppliers/{no}/active` | biz:stock |
| POST | `/biz/inventory/transfers` | biz:stock |
| GET | `/biz/inventory/transfers/{no}` | biz:stock |
| POST | `/biz/inventory/transfers/{no}/receive` | biz:stock |
| POST | `/biz/inventory/transfers/{no}/ship` | biz:stock |
| POST | `/biz/inventory/transfers/{no}/void` | biz:stock |
| GET | `/biz/store/{storeNo}/offline-sale` | biz:stock |
| POST | `/biz/store/{storeNo}/offline-sale` | biz:stock |
| POST | `/biz/store/{storeNo}/offline-sale/{docNo}/revoke` | biz:stock |
| GET | `/biz/store/{storeNo}/sell-rules` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-alignment` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-overview` | biz:stock |
| POST | `/biz/store/{storeNo}/stock-overview/batch` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-reconcile/records` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-reconcile/{operationNo}` | biz:stock |
| GET | `/biz/store/{storeNo}/stock-sync` | biz:stock |

</details>

<details><summary><code>biz:store</code> 店铺装修与配送规则 —— 29 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/appointment-slots/{slotNo}/close` | biz:store |
| GET | `/biz/communities/applies` | biz:store |
| POST | `/biz/communities/apply` | biz:store |
| POST | `/biz/communities/from-map` | biz:store |
| GET | `/biz/delivery/rule` | biz:store |
| POST | `/biz/delivery/rule` | biz:store |
| GET | `/biz/express/companies` | (biz:ship 或 biz:store) |
| POST | `/biz/pickup-points` | biz:store |
| GET | `/biz/pickup-points/candidates` | biz:store |
| GET | `/biz/qualifications` | biz:store |
| POST | `/biz/qualifications/recognize` | biz:store |
| POST | `/biz/qualifications/save` | biz:store |
| GET | `/biz/store` | biz:store |
| POST | `/biz/store` | biz:store |
| POST | `/biz/store/announcement` | biz:store |
| POST | `/biz/store/announcement/recent/remove` | biz:store |
| GET | `/biz/store/poster` | biz:store |
| GET | `/biz/store/qrcode` | biz:store |
| POST | `/biz/store/scope-preview` | biz:store |
| POST | `/biz/store/settings/hours` | biz:store |
| GET | `/biz/store/share-kit` | biz:store |
| GET | `/biz/store/{storeNo}/categories` | biz:store |
| GET | `/biz/store/{storeNo}/pay-setting` | biz:store |
| GET | `/biz/store/{storeNo}/ship-setting` | biz:store |
| GET | `/biz/stores/{storeNo}/appointment-slots` | biz:store |
| POST | `/biz/stores/{storeNo}/appointment-slots` | biz:store |
| GET | `/biz/stores/{storeNo}/fulfillment` | biz:store |
| PUT | `/biz/stores/{storeNo}/fulfillment` | biz:store |
| GET | `/biz/stores/{storeNo}/fulfillment/{channel}/impact` | biz:store |

</details>

<details><summary><code>biz:store:admin</code>  —— 53 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/inventory/locations` | biz:store:admin |
| PUT | `/biz/inventory/locations/{id}/source` | biz:store:admin |
| PUT | `/biz/member-settings` | biz:store:admin |
| GET | `/biz/personnel` | biz:store:admin |
| POST | `/biz/personnel` | biz:store:admin |
| POST | `/biz/personnel/{personnelNo}/account-links` | biz:store:admin |
| POST | `/biz/personnel/{personnelNo}/assignments` | biz:store:admin |
| POST | `/biz/personnel/{personnelNo}/status-changes` | biz:store:admin |
| GET | `/biz/plan` | biz:store:admin |
| GET | `/biz/plan/purchases` | biz:store:admin |
| POST | `/biz/plan/purchases` | biz:store:admin |
| GET | `/biz/plan/purchases/offers` | biz:store:admin |
| GET | `/biz/plan/purchases/{orderNo}` | biz:store:admin |
| POST | `/biz/plan/purchases/{orderNo}/cancel` | biz:store:admin |
| GET | `/biz/plan/purchases/{orderNo}/cash-refunds` | biz:store:admin |
| POST | `/biz/plan/purchases/{orderNo}/claims` | biz:store:admin |
| POST | `/biz/plan/purchases/{orderNo}/evidence` | biz:store:admin |
| POST | `/biz/plan/purchases/{orderNo}/evidence/content` | biz:store:admin |
| GET | `/biz/plan/purchases/{orderNo}/evidence/url` | biz:store:admin |
| POST | `/biz/plan/trial` | biz:store:admin |
| GET | `/biz/resources` | biz:store:admin |
| POST | `/biz/resources` | biz:store:admin |
| GET | `/biz/role-perms` | biz:store:admin |
| POST | `/biz/role/{roleCode}` | biz:store:admin |
| POST | `/biz/role/{roleCode}/delete` | biz:store:admin |
| GET | `/biz/roles` | biz:store:admin |
| POST | `/biz/roles` | biz:store:admin |
| POST | `/biz/schedule-rules` | biz:store:admin |
| GET | `/biz/schedules` | biz:store:admin |
| GET | `/biz/staff` | biz:store:admin |
| POST | `/biz/staff` | biz:store:admin |
| GET | `/biz/staff/logs` | biz:store:admin |
| POST | `/biz/staff/{mchAccountNo}/status` | biz:store:admin |
| POST | `/biz/staff/{mchAccountNo}/store` | biz:store:admin |
| POST | `/biz/store/create` | biz:store:admin |
| POST | `/biz/store/settings/basic` | biz:store:admin |
| POST | `/biz/store/settings/contact` | biz:store:admin |
| POST | `/biz/store/{storeNo}/categories` | biz:store:admin |
| POST | `/biz/store/{storeNo}/default` | biz:store:admin |
| POST | `/biz/store/{storeNo}/entity` | biz:store:admin |
| GET | `/biz/store/{storeNo}/entity/relink-check` | biz:store:admin |
| PUT | `/biz/store/{storeNo}/pay-setting` | biz:store:admin |
| POST | `/biz/store/{storeNo}/payment` | biz:store:admin |
| POST | `/biz/store/{storeNo}/rename` | biz:store:admin |
| PUT | `/biz/store/{storeNo}/sell-rules` | biz:store:admin |
| PUT | `/biz/store/{storeNo}/ship-setting` | biz:store:admin |
| POST | `/biz/store/{storeNo}/slug` | biz:store:admin |
| POST | `/biz/store/{storeNo}/status` | biz:store:admin |
| POST | `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}/confirm` | biz:store:admin |
| POST | `/biz/store/{storeNo}/stock-alignment/confirm` | biz:store:admin |
| POST | `/biz/store/{storeNo}/stock-alignment/preview` | biz:store:admin |
| POST | `/biz/store/{storeNo}/stock-reconcile` | biz:store:admin |
| PUT | `/biz/store/{storeNo}/stock-sync` | biz:store:admin |

</details>

<details><summary><code>biz:verify</code> 核销取货 —— 8 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/coupon-redeem` | biz:verify |
| GET | `/biz/coupon-redeem/{code}` | biz:verify |
| GET | `/biz/dashboard/todo` | (biz:order:view 或 biz:receive 或 biz:verify 或 biz:ship 或 biz:aftersale 或 biz:review 或 biz:campaign) |
| GET | `/biz/pickup/orders` | biz:verify |
| GET | `/biz/pickup/overview` | biz:verify |
| POST | `/biz/pickup/verify` | biz:verify |
| POST | `/biz/pickup/verify/batch` | biz:verify |
| GET | `/biz/pickup/verify/search` | biz:verify |

</details>

<details><summary><code>mch:pay:app-grant</code> 授权第三方代本店收款或退款 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pay/app-grant` | mch:pay:app-grant |
| POST | `/biz/pay/app-grant` | mch:pay:app-grant |
| DELETE | `/biz/pay/app-grant/{id}` | mch:pay:app-grant |
| GET | `/biz/payment/app-grant` | mch:pay:app-grant |
| POST | `/biz/payment/app-grant` | mch:pay:app-grant |
| DELETE | `/biz/payment/app-grant/{id}` | mch:pay:app-grant |

</details>

<details><summary><code>mch:pay:collect</code> 代客收款（出动态收款码） —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/pay/collection` | mch:pay:collect |
| POST | `/biz/payment/collection` | mch:pay:collect |
| POST | `/biz/payment/links/{payOrderNo}/reissue` | mch:pay:collect |
| POST | `/biz/payment/links/{payOrderNo}/void` | mch:pay:collect |

</details>

<details><summary><code>mch:pay:offline:manage</code> 更正与作废 App 外收款 —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/payment/offline-records/{recordNo}/correct` | mch:pay:offline:manage |
| POST | `/biz/payment/offline-records/{recordNo}/void` | mch:pay:offline:manage |

</details>

<details><summary><code>mch:pay:offline:record</code> 记录 App 外收款 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/payment/offline-records` | mch:pay:offline:record |

</details>

<details><summary><code>mch:pay:order:view</code> 查看本店收款记录（列表与详情） —— 10 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/payment/collect-status` | mch:pay:order:view |
| GET | `/biz/payment/events` | mch:pay:order:view |
| GET | `/biz/payment/links` | mch:pay:order:view |
| GET | `/biz/payment/offline-records` | mch:pay:order:view |
| GET | `/biz/payment/offline-records/summary` | mch:pay:order:view |
| GET | `/biz/payment/orders` | mch:pay:order:view |
| GET | `/biz/payment/orders/lookup` | mch:pay:order:view |
| GET | `/biz/payment/orders/{payOrderNo}` | mch:pay:order:view |
| POST | `/biz/payment/orders/{payOrderNo}/query` | mch:pay:order:view |
| GET | `/biz/stores/{storeNo}/receivables/{bizNo}` | mch:pay:order:view |

</details>

<details><summary><code>mch:pay:qr</code> 生成与停用本店收款码 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pay/qr-code` | mch:pay:qr |
| POST | `/biz/pay/qr-code` | mch:pay:qr |
| PUT | `/biz/pay/qr-code/{qrCode}/disabled` | mch:pay:qr |
| GET | `/biz/payment/qr-code` | mch:pay:qr |
| POST | `/biz/payment/qr-code` | mch:pay:qr |
| PUT | `/biz/payment/qr-code/{qrCode}/disabled` | mch:pay:qr |

</details>

<details><summary><code>mch:pay:refund</code> 发起原路退款 —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pay/refund` | (mch:pay:refund 或 mch:pay:statement) |
| POST | `/biz/pay/refund` | mch:pay:refund |
| GET | `/biz/payment/refund` | (mch:pay:refund 或 mch:pay:statement) |
| POST | `/biz/payment/refund` | mch:pay:refund |

</details>

<details><summary><code>mch:pay:statement</code> 看本店收款流水 —— 5 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/pay/refund` | (mch:pay:refund 或 mch:pay:statement) |
| GET | `/biz/pay/statement` | mch:pay:statement |
| GET | `/biz/payment/orders/summary` | mch:pay:statement |
| GET | `/biz/payment/refund` | (mch:pay:refund 或 mch:pay:statement) |
| GET | `/biz/payment/statement` | mch:pay:statement |

</details>

<details><summary><code>pay:cash:receive</code> 确认线下实收 —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/checkout/{operationNo}/offline-receipts` | pay:cash:receive |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/{orderNo}/manual-receipts` | (pay:cash:receive 且 trade:manual-service:confirm) |

</details>

<details><summary><code>pay:ent:issue</code> 定向发放权益 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/entitlement-issue-batches` | pay:ent:issue |

</details>

<details><summary><code>pay:ent:issue:manage</code> 管理权益发行规则 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| PUT | `/biz/entitlement-templates/{templateNo}/issue-policy` | pay:ent:issue:manage |

</details>

<details><summary><code>pay:ent:read</code> 查看权益与核销记录 —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/entitlement-issue-batches/{batchNo}` | pay:ent:read |
| GET | `/biz/entitlement-usage` | pay:ent:read |
| GET | `/biz/entitlement-uses/{operationNo}` | pay:ent:read |
| GET | `/biz/entitlements/{instanceNo}` | pay:ent:read |

</details>

<details><summary><code>pay:ent:template:publish</code> 发布与停用权益模板 —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/entitlement-templates/{templateNo}/publish` | pay:ent:template:publish |
| PUT | `/biz/entitlement-templates/{templateNo}/status` | pay:ent:template:publish |

</details>

<details><summary><code>pay:ent:template:read</code> 查看权益模板 —— 1 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/entitlement-templates` | pay:ent:template:read |

</details>

<details><summary><code>pay:ent:template:write</code> 编辑权益模板 —— 2 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/entitlement-templates` | pay:ent:template:write |
| PUT | `/biz/entitlement-templates/{templateNo}/draft` | pay:ent:template:write |

</details>

<details><summary><code>pay:ent:use</code> 核销权益 —— 3 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/entitlement-uses` | pay:ent:use |
| POST | `/biz/entitlement-uses/preview` | pay:ent:use |
| POST | `/biz/entitlement-uses/{operationNo}/cancel` | pay:ent:use |

</details>

<details><summary><code>pay:sv:account:read</code> 查看顾客储值可用余额 —— 13 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/stored-value/account-directory` | pay:sv:account:read |
| GET | `/biz/stored-value/accounts` | pay:sv:account:read |
| GET | `/biz/stored-value/accounts/{accountRef}` | pay:sv:account:read |
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/records` | pay:sv:account:read |
| GET | `/biz/stored-value/records/{recordNo}` | pay:sv:account:read |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts` | pay:sv:account:read |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records` | pay:sv:account:read |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records` | pay:sv:account:read |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}` | pay:sv:account:read |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) |

</details>

<details><summary><code>pay:sv:consume:create</code> 使用储值核销服务 —— 5 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/stored-value/consumptions` | pay:sv:consume:create |
| GET | `/biz/stored-value/consumptions/orders/{orderNo}` | pay:sv:consume:create |
| POST | `/biz/stored-value/consumptions/orders/{orderNo}/cancel` | pay:sv:consume:create |
| POST | `/biz/stored-value/consumptions/preview` | pay:sv:consume:create |
| POST | `/biz/stored-value/consumptions/resolve` | pay:sv:consume:create |

</details>

<details><summary><code>pay:sv:plan:manage</code> 管理门店储值方案 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/plans` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| POST | `/biz/stored-value/plans` | pay:sv:plan:manage |
| GET | `/biz/stored-value/plans/{planNo}` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| PUT | `/biz/stored-value/plans/{planNo}/status` | pay:sv:plan:manage |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) |

</details>

<details><summary><code>pay:sv:plan:read</code> 查看门店储值方案 —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/plans` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/plans/{planNo}` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) |

</details>

<details><summary><code>pay:sv:recharge:create</code> 办理与取消未实收储值 —— 24 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/customer-candidates` | (biz:customer 且 pay:sv:recharge:create) |
| POST | `/biz/stored-value/customer-selections` | (biz:customer 且 pay:sv:recharge:create) |
| POST | `/biz/stored-value/plan-recharges` | pay:sv:recharge:create |
| POST | `/biz/stored-value/plan-recharges/cancel-create` | pay:sv:recharge:create |
| POST | `/biz/stored-value/plan-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) |
| POST | `/biz/stored-value/plan-recharges/resolve` | pay:sv:recharge:create |
| GET | `/biz/stored-value/plan-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/submit` | pay:sv:recharge:create |
| GET | `/biz/stored-value/plans` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/plans/{planNo}` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |
| POST | `/biz/stored-value/recharges` | pay:sv:recharge:create |
| POST | `/biz/stored-value/recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |
| POST | `/biz/stored-value/recharges/{rechargeNo}/submit` | pay:sv:recharge:create |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create` | pay:sv:recharge:create |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/resolve` | pay:sv:recharge:create |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) |
| GET | `/biz/stores/{storeNo}/stored-value/recharge-options` | pay:sv:recharge:create |

</details>

<details><summary><code>pay:sv:recharge:read</code> 查看储值办理进度 —— 3 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| GET | `/biz/stored-value/plan-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |
| GET | `/biz/stored-value/recharges/{rechargeNo}` | pay:sv:recharge:read |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |

</details>

<details><summary><code>pay:sv:recharge:receive</code> 核验储值实收 —— 4 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/stored-value/plan-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/receive` | pay:sv:recharge:receive |
| POST | `/biz/stored-value/recharges/{rechargeNo}/receive` | pay:sv:recharge:receive |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) |

</details>

<details><summary><code>trade:checkout:prepare</code> 准备服务购买报价 —— 6 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/service-orders/counter-quotes` | (trade:checkout:prepare 且 biz:order:view) |
| GET | `/biz/service-orders/counter-quotes/{operationNo}` | (trade:checkout:prepare 且 biz:order:view) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| GET | `/biz/service-orders/counter-quotes/{operationNo}/state` | (trade:checkout:prepare 且 biz:order:view) |
| POST | `/biz/service-orders/manual-quotes` | trade:checkout:prepare |

</details>

<details><summary><code>trade:manual-service:confirm</code> 人工确认服务收款 —— 3 条</summary>

| 方法 | 地址 | 完整的权限式 |
|---|---|---|
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |
| POST | `/biz/service-orders/{orderNo}/manual-receipts` | (pay:cash:receive 且 trade:manual-service:confirm) |

</details>

### 1.4 管理范围的码表

管理范围的权限不看门店角色，看「管理空间里的授权」：谁、对哪个资源、有哪个码。码表是现成的（`ManagementScopeServiceImpl`），授权在 `mch_management_grant`。

| 资源类型 | 这类资源上可以授的码 |
|---|---|
| `SPACE` | `management.enter`、`management.resource.read`、`management.grant.manage`、`brand.manage`、`brand.change.read`、`brand.change.decide`、`sv.policy.read`、`sv.policy.manage`、`sv.store.activity.approve`、`sv.store.report.all` |
| `BRAND` | `promotion.activity.read`、`promotion.activity.manage`、`promotion.activity.publish`、`promotion.coupon.read`、`promotion.coupon.manage`、`promotion.coupon.issue`、`promotion.cost.rule.manage`、`brand.read`、`brand.manage`、`brand.audit.read`、`brand.change.read`、`brand.change.decide`、`sv.issuer.read`、`sv.issuer.configure`、`sv.policy.read`、`sv.policy.manage`、`sv.brand.activity.read`、`sv.brand.activity.manage`、`sv.brand.activity.publish`、`sv.brand.activity.approve`、`sv.brand.report`、`sv.activity.report` |
| `STORE` | `promotion.participate`、`store.read`、`store.brand.change`、`sv.issuer.read`、`sv.issuer.configure`、`sv.policy.read`、`sv.policy.manage`、`sv.store.activity.read`、`sv.store.activity.manage`、`sv.store.activity.publish`、`sv.store.report`、`sv.activity.report` |
| `ENTITY` | `promotion.sponsor.authorize`、`promotion.cost.read`、`promotion.cost.authorize`、`promotion.cost.confirm`、`entity.read`、`payment.merchant.read`、`sv.issuer.settlement.authorize`、`sv.issuer.collection.confirm`、`sv.settlement.read`、`sv.settlement.generate`、`sv.settlement.reconcile`、`sv.settlement.dispute`、`sv.settlement.dispute.resolve`、`sv.settlement.adjust`、`sv.settlement.payment.register`、`sv.settlement.receipt.confirm`、`sv.settlement.payment.void`、`sv.settlement.audit` |
| `PAYMENT_MERCHANT` | `payment.merchant.read` |

空间的属主对空间本身和空间下的品牌天然有权；对门店、主体没有天然权限，要靠授权。

### 1.5 「只问是不是商家」的 123 条入口：现在与建议

这些入口的注解只写了「是不是商家」，网关只要求登录；真正的权限在业务代码里按管理范围判。

| 状态 | 条数 |
|---|---:|
| 已配 | 72 |
| 保持登录即可 | 38 |
| 待确认 | 10 |
| 不处理 | 3 |

#### 已配（72）

| 方法 | 地址 | 建议的规则 | 依据 |
|---|---|---|---|
| GET | `/biz/management/brand-changes/{changeNo}` | `management.enter @SPACE(任一)` | BrandService.change：resolve；之后按变更单上的门店或品牌判，留给业务 |
| POST | `/biz/management/brand-changes/{changeNo}/decisions` | `store.brand.change @STORE(任一)` | decide：require(STORE, store.brand.change)，门店从变更单查出 |
| GET | `/biz/management/brands` | `management.enter @SPACE(managementNo)` | queries.list：resolve |
| POST | `/biz/management/brands` | `brand.manage @SPACE(任一)` | BrandService.create：require(SPACE, brand.manage) |
| GET | `/biz/management/brands/{brandNo}` | `brand.read @BRAND(brandNo)` | detail：require(BRAND, brand.read) |
| PUT | `/biz/management/brands/{brandNo}` | `brand.manage @BRAND(brandNo)` | mutate |
| GET | `/biz/management/brands/{brandNo}/audit-events` | `brand.audit.read @BRAND(brandNo)` | queries.audit |
| GET | `/biz/management/brands/{brandNo}/commands` | `brand.manage @BRAND(brandNo)` | editResult |
| PUT | `/biz/management/brands/{brandNo}/legal-entity` | `brand.manage @BRAND(brandNo)` | mutate |
| GET | `/biz/management/brands/{brandNo}/legal-entity-candidates` | `brand.manage @BRAND(brandNo)` | legalEntityCandidates |
| GET | `/biz/management/brands/{brandNo}/legal-entity/commands` | `brand.manage @BRAND(brandNo)` | legalEntityResult |
| GET | `/biz/management/brands/{brandNo}/marketing/activities` | `management.enter @SPACE(managementNo)` | 同上 |
| GET | `/biz/management/brands/{brandNo}/marketing/commands` | `management.enter @SPACE(managementNo)` | 同上 |
| POST | `/biz/management/brands/{brandNo}/marketing/commands` | `management.enter @SPACE(managementNo)` | 同上；具体动作的码在请求体里 |
| GET | `/biz/management/brands/{brandNo}/marketing/context` | `management.enter @SPACE(managementNo)` | BrandPromotionAuthority：resolve；按活动类型的码留给业务 |
| GET | `/biz/management/brands/{brandNo}/marketing/cost-rules` | `management.enter @SPACE(managementNo)` | 同上 |
| GET | `/biz/management/brands/{brandNo}/marketing/cost-statements` | `management.enter @SPACE(managementNo)` | 同上 |
| GET | `/biz/management/brands/{brandNo}/marketing/coupon-issues` | `management.enter @SPACE(managementNo)` | 同上 |
| GET | `/biz/management/brands/{brandNo}/marketing/coupons` | `management.enter @SPACE(managementNo)` | 同上 |
| POST | `/biz/management/brands/{brandNo}/status-changes` | `brand.manage @BRAND(brandNo)` | mutate |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities` | `sv.brand.activity.read @BRAND(brandNo)` | 控制器 list：context(…,"sv.brand.activity.read") |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | `sv.brand.activity.read @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| PUT | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | `sv.brand.activity.publish @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-context` | `sv.brand.activity.read @BRAND(brandNo)` | 控制器 context：同上 |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-presets` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-schedules` | `sv.brand.activity.manage @BRAND(brandNo)` | 品牌储值活动：码在控制器里直接看得到 |
| GET | `/biz/management/brands/{brandNo}/stored-value/issuer` | `sv.issuer.read @BRAND(brandNo)` | IssuerAuthority.readScope → sv.issuer.read |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer` | `sv.issuer.configure @BRAND(brandNo)` | configureScope → sv.issuer.configure；另要结算主体上的 sv.issuer.settlement.authorize（主体号在请求体里，留给业务） |
| GET | `/biz/management/brands/{brandNo}/stored-value/store-breakdown` | `sv.brand.report @BRAND(brandNo)` | 同上 |
| GET | `/biz/management/brands/{brandNo}/stored-value/summary` | `sv.brand.report @BRAND(brandNo)` | reportLookup(BRAND, …, sv.brand.report) |
| GET | `/biz/management/brands/{brandNo}/stores` | `brand.read @BRAND(brandNo)` | 先 detail |
| GET | `/biz/management/entities/{entityNo}/payment-merchants` | `(entity.read @ENTITY(entityNo) 且 payment.merchant.read @ENTITY(entityNo))` | PaymentMerchantScope.candidates；带门店号时另要 store.read，留给业务 |
| GET | `/biz/management/spaces/{managementNo}/entities` | `management.enter @SPACE(managementNo)` | queries.list：resolve |
| GET | `/biz/management/spaces/{managementNo}/grant-commands/{action}` | `(management.enter @SPACE(managementNo) 且 management.resource.read @SPACE(managementNo) 且 management.grant.manage @SPACE(managementNo))` | grantResult：三条 require |
| GET | `/biz/management/spaces/{managementNo}/grant-options` | `(management.resource.read @SPACE(managementNo) 且 management.grant.manage @SPACE(managementNo) 且 brand.read @BRAND(brandNo))` | queries.grantOptions：三条 require |
| POST | `/biz/management/spaces/{managementNo}/grants` | `management.grant.manage @SPACE(managementNo)` | scopes.grant |
| DELETE | `/biz/management/spaces/{managementNo}/grants/{grantNo}` | `management.grant.manage @SPACE(managementNo)` | scopes.revoke |
| GET | `/biz/management/spaces/{managementNo}/recipients` | `(management.resource.read @SPACE(managementNo) 且 management.grant.manage @SPACE(managementNo))` | queries.recipients |
| GET | `/biz/management/spaces/{managementNo}/resources` | `management.resource.read @SPACE(managementNo)` | queries.resources；看授权视图时另要 grant.manage，留给业务 |
| GET | `/biz/management/spaces/{managementNo}/stores` | `management.enter @SPACE(managementNo)` | queries.list：resolve |
| GET | `/biz/management/stored-value/activities/{activityNo}/summary` | `(sv.activity.report @STORE(任一) 或 sv.activity.report @BRAND(任一))` | activityResource → sv.activity.report；发行方属于门店还是品牌事先不知道 |
| GET | `/biz/management/stored-value/financial-resources` | `sv.settlement.read @ENTITY(任一)` | readableDirectory：按有 sv.settlement.read 的主体过滤 |
| GET | `/biz/management/stored-value/issuers/{issuerNo}/collection-routes` | `(sv.issuer.read @STORE(任一) 或 sv.issuer.read @BRAND(任一))` | 按发行方号查出它属于门店还是品牌，再 readScope；类型事先不知道 |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | `(sv.issuer.configure @STORE(任一) 或 sv.issuer.configure @BRAND(任一))` | 同上，configureScope |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | `(sv.issuer.configure @STORE(任一) 或 sv.issuer.configure @BRAND(任一))` | 同上 |
| GET | `/biz/management/stored-value/settlement-bills` | `sv.settlement.read @ENTITY(任一)` | 支付域 QueryService：至少一个主体有 sv.settlement.read |
| POST | `/biz/management/stored-value/settlement-bills/generations` | `sv.settlement.generate @ENTITY(任一)` | SettlementService.GENERATE |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}` | `sv.settlement.read @ENTITY(任一)` | detail：requireEither(…, sv.settlement.read) |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/actions` | `sv.settlement.read @ENTITY(任一)` | actions 内部调 detail |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/adjustments` | `sv.settlement.adjust @ENTITY(任一)` | requireEither |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | `sv.settlement.dispute.resolve @ENTITY(任一)` | requireEither |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/disputes` | `sv.settlement.dispute @ENTITY(任一)` | requireEither |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/events` | `sv.settlement.audit @ENTITY(任一)` | events 要的是**审计**权限，不是读 |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/items` | `sv.settlement.read @ENTITY(任一)` | items |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | `sv.settlement.read @ENTITY(任一)` | payments |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | `sv.settlement.payment.register @ENTITY(任一)` | PaymentService.REGISTER |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | `sv.settlement.reconcile @ENTITY(任一)` | requireParty(…, sv.settlement.reconcile) |
| GET | `/biz/management/stored-value/settlement-entity-candidates` | `(sv.issuer.configure @STORE(任一) 或 sv.issuer.configure @BRAND(任一))` | settlementCandidates → sv.issuer.configure；类型在查询参数 scopeType 里，是变量 |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | `sv.settlement.receipt.confirm @ENTITY(任一)` | PaymentService.RECEIVE |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/voids` | `sv.settlement.payment.void @ENTITY(任一)` | PaymentService.VOID |
| GET | `/biz/management/stored-value/store-issuer-summary` | `sv.store.report.all @SPACE(任一)` | FinancialAuthority：require(SPACE, sv.store.report.all) |
| GET | `/biz/management/stores/{storeNo}/stored-value/issuer` | `sv.issuer.read @STORE(storeNo)` | readScope(STORE) |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer` | `sv.issuer.configure @STORE(storeNo)` | configureScope(STORE)；结算主体的授权留给业务 |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh` | `sv.issuer.read @STORE(storeNo)` | readScope(STORE) |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` | `(management.grant.manage @SPACE(任一) 且 store.read @STORE(storeNo))` | prepareSettings：两条 require |
| GET | `/biz/stores/{storeNo}/operating-context` | `store.read @STORE(storeNo)` | queries.operating |
| GET | `/biz/stores/{storeNo}/stored-value/store-summary` | `(sv.store.report @STORE(storeNo) 或 sv.store.report.all @SPACE(任一))` | reportLookup(STORE)：门店上的 sv.store.report，或空间的 sv.store.report.all 加本人是该店主体的属主（属主这一半留给业务） |

#### 待确认（10）

| 方法 | 地址 | 建议的规则 | 依据 |
|---|---|---|---|
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer/activation` | — | 品牌发行方激活，没读完 |
| POST | `/biz/management/entities/{entityNo}/stored-value/financial-permissions` | — | 只认主体属主，授权表里没有对应的码 |
| GET | `/biz/management/stored-value/financial-brand-changes/{changeNo}` | — | 同上 |
| POST | `/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions` | — | 同上 |
| GET | `/biz/management/stored-value/issuers/{issuerNo}` | — | BizStoredValueIssuerController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions` | — | 在付款服务里按原命令判，没读到固定的码 |
| GET | `/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original` | — | 在品牌资金变更的协调器里判，没读完 |
| POST | `/biz/management/stores/{storeNo}/stored-value/financial-brand-changes` | — | 同上 |
| POST | `/biz/stores/{storeNo}/brand-changes` | — | 同上 |
| POST | `/biz/stores/{storeNo}/brand-changes/check` | — | 管理空间号在请求体里；具体要哪个码没读确定 |

#### 保持登录即可（38）

| 方法 | 地址 | 建议的规则 | 依据 |
|---|---|---|---|
| GET | `/biz/app/legal-subjects` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/app/operating-entities` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/app/operating-entities` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/app/operating-entities/context` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/app/operating-entities/create-results/{key}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/app/operating-entities/{id}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/business-types` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/business-types/{typeCode}/feature-matrix` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/business-types/{typeCode}/plans` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities/create-results/{requestNo}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/channel-requests` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities/{entity}/commands/{request}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/contact` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/files` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities/{entity}/files/{file}/preview` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities/{entity}/profile-draft` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/profile-draft` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entities/{entity}/profile-template` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/recognitions` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/review-submissions` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/revision-discards` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entities/{entity}/revisions` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/entity-recognitions` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/entity/{entityNo}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/feedback` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/feedback/mine` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/management/context` | — | 账户级：列出我能进的管理空间 |
| POST | `/biz/management/spaces` | — | 账户级：建管理空间，凭的是本人是某个主体的属主，授权表里没有对应的码 |
| GET | `/biz/management/stored-value/financial-capability` | — | 只返回就绪开关 |
| GET | `/biz/merchant/store-creation-context` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| POST | `/biz/merchant/store-openings` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/merchant/store-openings/{requestNo}` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/merchant/store-setup` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/store/settings` | — | 账户级：建店、主体资料、业务类型、问题回报等，本来就不按角色判 |
| GET | `/biz/stored-value/brand-capability` | — | 只返回能力开关 |
| GET | `/biz/stored-value/governance-capability` | — | 只返回能力开关 |

#### 不处理（3）

| 方法 | 地址 | 建议的规则 | 依据 |
|---|---|---|---|
| POST | `/biz/management/stored-value/migration-manifests` | — | 迁移清单；准入表里对新模型门店关闭 |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/map` | — | 迁移清单；准入表里对新模型门店关闭 |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/pause` | — | 迁移清单；准入表里对新模型门店关闭 |

## 二、C 端

C 端没有角色。一个入口对谁开，只有两档：**访客**（不登录）与**登录的买家**；登录之后能碰哪条数据（自己的订单、自己的地址）由业务按属主判，不是角色。

### 2.1 功能域 × 身份

| 功能域 | 要登录（已配） | 公开（端上声明不用登录） | 公开（实测匿名能进，端上没有声明） | 未定（端上没有声明，写接口没法实测） | 登录入口（锁定） | 合计 |
|---|---:|---:|---:|---:|---:|---:|
| `user` | 15 | 1 | — | 1 | 5 | 22 |
| `order` | 16 | — | — | — | — | 16 |
| `group-buy` | 6 | 2 | — | 1 | — | 9 |
| `merchant` | 4 | 4 | 1 | — | — | 9 |
| `store` | 2 | 6 | — | 1 | — | 9 |
| `group-request` | 4 | 2 | 2 | — | — | 8 |
| `goods` | — | 6 | 1 | — | — | 7 |
| `stored-value` | 6 | — | 1 | — | — | 7 |
| `after-sale` | 5 | 1 | — | — | — | 6 |
| `checkout` | 2 | — | — | 4 | — | 6 |
| `entitlements` | 4 | — | — | 2 | — | 6 |
| `message` | 5 | — | — | — | — | 5 |
| `cart` | 4 | — | — | — | — | 4 |
| `community` | — | 4 | — | — | — | 4 |
| `coupon` | 3 | 1 | — | — | — | 4 |
| `favorite` | 4 | — | — | — | — | 4 |
| `invoice` | 3 | — | — | — | — | 3 |
| `review` | 2 | 1 | — | — | — | 3 |
| `ticket` | 2 | — | — | 1 | — | 3 |
| `track` | 1 | 2 | — | — | — | 3 |
| `entitlement-claims` | 1 | — | — | 1 | — | 2 |
| `entitlement-service-orders` | 2 | — | — | — | — | 2 |
| `entitlement-usage` | 2 | — | — | — | — | 2 |
| `my-memberships` | 2 | — | — | — | — | 2 |
| `points` | 2 | — | — | — | — | 2 |
| `push-token` | 2 | — | — | — | — | 2 |
| `search` | — | — | 2 | — | — | 2 |
| `topics` | — | — | 2 | — | — | 2 |
| `wx` | — | — | 1 | 1 | — | 2 |
| `attribution` | — | — | — | 1 | — | 1 |
| `category` | — | — | 1 | — | — | 1 |
| `config` | — | 1 | — | — | — | 1 |
| `entitlement-quotes` | — | — | — | 1 | — | 1 |
| `fission` | 1 | — | — | — | — | 1 |
| `help` | — | — | 1 | — | — | 1 |
| `location` | — | 1 | — | — | — | 1 |
| `member-code` | 1 | — | — | — | — | 1 |
| `member-reach` | 1 | — | — | — | — | 1 |
| `my-coupons` | 1 | — | — | — | — | 1 |
| `orders` | 1 | — | — | — | — | 1 |
| `pickup` | — | — | 1 | — | — | 1 |
| `place` | — | 1 | — | — | — | 1 |
| `question` | 1 | — | — | — | — | 1 |
| `regions` | — | 1 | — | — | — | 1 |
| `risk` | — | — | — | 1 | — | 1 |
| `stores` | — | — | 1 | — | — | 1 |
| `upload` | 1 | — | — | — | — | 1 |
| **合计** | 106 | 34 | 14 | 15 | 5 | 174 |

### 2.2 还没定的入口

下面 15 条写接口，端上没有声明要不要登录，也没法安全地实测（匿名发写请求可能真的改数据）。要逐条定：是要登录，还是确实对访客开放。

| 方法 | 地址 | 入口 |
|---|---|---|
| POST | `/mp/attribution/report` | MpStoreController#report |
| POST | `/mp/checkout/{operationNo}/confirmations` | MpCheckoutController#confirm |
| POST | `/mp/checkout/{operationNo}/orders` | MpCheckoutController#place |
| POST | `/mp/checkout/{operationNo}/payments` | MpCheckoutController#initiatePayment |
| POST | `/mp/checkout/{operationNo}/payments/lookup` | MpCheckoutController#lookupPayment |
| POST | `/mp/entitlement-claims` | MpEntitlementController#claim |
| POST | `/mp/entitlement-quotes` | MpEntitlementQuoteController#quote |
| POST | `/mp/entitlements/{instanceNo}/activation-quotes` | MpEntitlementController#activation |
| POST | `/mp/entitlements/{instanceNo}/redeem-codes` | MpEntitlementController#code |
| POST | `/mp/group-buy/{groupNo}/join` | MpGroupController#join |
| POST | `/mp/risk/appeal` | MpRiskController#appeal |
| POST | `/mp/store/{no}/rebuy` | MpStoreController#rebuy |
| POST | `/mp/ticket` | MpMessageController#createTicket |
| POST | `/mp/user/switch-to-merchant` | MpUserController#switchToMerchant |
| POST | `/mp/wx/callback` | MpWxCallbackController#receive |

另有 14 条读接口，端上没有声明，实测匿名能进。它们现在按公开处理；如果其中有不该公开的，在库里配成要登录即可：

| 方法 | 地址 |
|---|---|
| GET | `/mp/category/tree` |
| GET | `/mp/goods/{goodsNo}/sku-price` |
| GET | `/mp/group-request/{requestNo}/price-history` |
| GET | `/mp/group-request/{requestNo}/quotes` |
| GET | `/mp/help/faq` |
| GET | `/mp/merchant/{merchantNo}/score` |
| GET | `/mp/pickup/{pickupNo}` |
| GET | `/mp/search/hot` |
| GET | `/mp/search/suggest` |
| GET | `/mp/stored-value/consumptions` |
| GET | `/mp/stores/{storeNo}/appointment-slots` |
| GET | `/mp/topics` |
| GET | `/mp/topics/{topicNo}/goods` |
| GET | `/mp/wx/callback` |

### 2.3 全部入口

<details><summary><code>user</code> —— 22 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/user/active-address` | 要登录（已配） |
| POST | `/mp/user/active-address/{addressId}` | 要登录（已配） |
| GET | `/mp/user/address` | 要登录（已配） |
| POST | `/mp/user/address` | 要登录（已配） |
| POST | `/mp/user/address/{addressId}/archive` | 要登录（已配） |
| POST | `/mp/user/address/{addressId}/default` | 要登录（已配） |
| POST | `/mp/user/avatar` | 要登录（已配） |
| POST | `/mp/user/community` | 要登录（已配） |
| POST | `/mp/user/deregister` | 要登录（已配） |
| POST | `/mp/user/login` | 登录入口（锁定） |
| POST | `/mp/user/login/phone` | 登录入口（锁定） |
| POST | `/mp/user/logout` | 登录入口（锁定） |
| POST | `/mp/user/otp/send` | 登录入口（锁定） |
| GET | `/mp/user/password` | 要登录（已配） |
| POST | `/mp/user/password` | 要登录（已配） |
| POST | `/mp/user/phone/bind` | 要登录（已配） |
| GET | `/mp/user/phone/capable` | 公开（端上声明不用登录） |
| POST | `/mp/user/phone/wx` | 要登录（已配） |
| GET | `/mp/user/profile` | 要登录（已配） |
| POST | `/mp/user/profile` | 要登录（已配） |
| POST | `/mp/user/switch-to-merchant` | 未定（端上没有声明，写接口没法实测） |
| POST | `/mp/user/token/refresh` | 登录入口（锁定） |

</details>

<details><summary><code>order</code> —— 16 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/order` | 要登录（已配） |
| POST | `/mp/order` | 要登录（已配） |
| POST | `/mp/order/capability` | 要登录（已配） |
| POST | `/mp/order/points-quote` | 要登录（已配） |
| POST | `/mp/order/preview` | 要登录（已配） |
| GET | `/mp/order/{orderNo}` | 要登录（已配） |
| POST | `/mp/order/{orderNo}/after-sale` | 要登录（已配） |
| POST | `/mp/order/{orderNo}/cancel` | 要登录（已配） |
| POST | `/mp/order/{orderNo}/confirm-receipt` | 要登录（已配） |
| POST | `/mp/order/{orderNo}/pay` | 要登录（已配） |
| GET | `/mp/order/{orderNo}/pay-method` | 要登录（已配） |
| GET | `/mp/order/{orderNo}/pay-result` | 要登录（已配） |
| GET | `/mp/order/{orderNo}/points-earning` | 要登录（已配） |
| GET | `/mp/order/{orderNo}/points-state` | 要登录（已配） |
| POST | `/mp/order/{orderNo}/reorder` | 要登录（已配） |
| GET | `/mp/order/{orderNo}/trace` | 要登录（已配） |

</details>

<details><summary><code>group-buy</code> —— 9 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/group-buy` | 公开（端上声明不用登录） |
| POST | `/mp/group-buy` | 要登录（已配） |
| GET | `/mp/group-buy/hosted` | 要登录（已配） |
| GET | `/mp/group-buy/mine` | 要登录（已配） |
| GET | `/mp/group-buy/{groupNo}` | 公开（端上声明不用登录） |
| POST | `/mp/group-buy/{groupNo}/join` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/group-buy/{groupNo}/orders` | 要登录（已配） |
| POST | `/mp/group-buy/{groupNo}/receive` | 要登录（已配） |
| POST | `/mp/group-buy/{groupNo}/verify` | 要登录（已配） |

</details>

<details><summary><code>merchant</code> —— 9 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/merchant` | 公开（端上声明不用登录） |
| GET | `/mp/merchant/apply` | 要登录（已配） |
| POST | `/mp/merchant/apply` | 要登录（已配） |
| POST | `/mp/merchant/apply/{applyNo}` | 要登录（已配） |
| GET | `/mp/merchant/promoted` | 公开（端上声明不用登录） |
| GET | `/mp/merchant/visited` | 要登录（已配） |
| GET | `/mp/merchant/{merchantNo}` | 公开（端上声明不用登录） |
| GET | `/mp/merchant/{merchantNo}/acode` | 公开（端上声明不用登录） |
| GET | `/mp/merchant/{merchantNo}/score` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>store</code> —— 9 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/store/by-code` | 公开（端上声明不用登录） |
| GET | `/mp/store/mine` | 公开（端上声明不用登录） |
| GET | `/mp/store/nearby` | 公开（端上声明不用登录） |
| GET | `/mp/store/{no}` | 公开（端上声明不用登录） |
| GET | `/mp/store/{no}/acode` | 公开（端上声明不用登录） |
| POST | `/mp/store/{no}/enter` | 要登录（已配） |
| GET | `/mp/store/{no}/frequent` | 要登录（已配） |
| GET | `/mp/store/{no}/goods` | 公开（端上声明不用登录） |
| POST | `/mp/store/{no}/rebuy` | 未定（端上没有声明，写接口没法实测） |

</details>

<details><summary><code>group-request</code> —— 8 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/group-request` | 公开（端上声明不用登录） |
| POST | `/mp/group-request` | 要登录（已配） |
| GET | `/mp/group-request/{requestNo}` | 公开（端上声明不用登录） |
| POST | `/mp/group-request/{requestNo}/choose` | 要登录（已配） |
| POST | `/mp/group-request/{requestNo}/confirm` | 要登录（已配） |
| POST | `/mp/group-request/{requestNo}/interest` | 要登录（已配） |
| GET | `/mp/group-request/{requestNo}/price-history` | 公开（实测匿名能进，端上没有声明） |
| GET | `/mp/group-request/{requestNo}/quotes` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>goods</code> —— 7 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/goods` | 公开（端上声明不用登录） |
| GET | `/mp/goods/promoted` | 公开（端上声明不用登录） |
| GET | `/mp/goods/{goodsNo}` | 公开（端上声明不用登录） |
| GET | `/mp/goods/{goodsNo}/batch` | 公开（端上声明不用登录） |
| GET | `/mp/goods/{goodsNo}/group` | 公开（端上声明不用登录） |
| GET | `/mp/goods/{goodsNo}/question` | 公开（端上声明不用登录） |
| GET | `/mp/goods/{goodsNo}/sku-price` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>stored-value</code> —— 7 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/stored-value/consumptions` | 公开（实测匿名能进，端上没有声明） |
| GET | `/mp/stored-value/issuer-records` | 要登录（已配） |
| GET | `/mp/stored-value/issuers` | 要登录（已配） |
| GET | `/mp/stored-value/notification-capability` | 要登录（已配） |
| GET | `/mp/stored-value/records/locate` | 要登录（已配） |
| GET | `/mp/stored-value/store/{storeNo}/records` | 要登录（已配） |
| GET | `/mp/stored-value/stores` | 要登录（已配） |

</details>

<details><summary><code>after-sale</code> —— 6 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/after-sale` | 要登录（已配） |
| GET | `/mp/after-sale/reasons` | 公开（端上声明不用登录） |
| GET | `/mp/after-sale/{afterSaleNo}` | 要登录（已配） |
| POST | `/mp/after-sale/{afterSaleNo}/cancel` | 要登录（已配） |
| POST | `/mp/after-sale/{afterSaleNo}/escalate` | 要登录（已配） |
| POST | `/mp/after-sale/{afterSaleNo}/ship` | 要登录（已配） |

</details>

<details><summary><code>checkout</code> —— 6 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/checkout/{operationNo}` | 要登录（已配） |
| POST | `/mp/checkout/{operationNo}/confirmations` | 未定（端上没有声明，写接口没法实测） |
| POST | `/mp/checkout/{operationNo}/orders` | 未定（端上没有声明，写接口没法实测） |
| POST | `/mp/checkout/{operationNo}/payments` | 未定（端上没有声明，写接口没法实测） |
| POST | `/mp/checkout/{operationNo}/payments/lookup` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/checkout/{operationNo}/payments/{paymentOperationNo}` | 要登录（已配） |

</details>

<details><summary><code>entitlements</code> —— 6 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/entitlements` | 要登录（已配） |
| GET | `/mp/entitlements/{instanceNo}` | 要登录（已配） |
| POST | `/mp/entitlements/{instanceNo}/activation-quotes` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/entitlements/{instanceNo}/allowances` | 要登录（已配） |
| POST | `/mp/entitlements/{instanceNo}/redeem-codes` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/entitlements/{instanceNo}/usage` | 要登录（已配） |

</details>

<details><summary><code>message</code> —— 5 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/message` | 要登录（已配） |
| POST | `/mp/message/read-all` | 要登录（已配） |
| POST | `/mp/message/subscribe` | 要登录（已配） |
| GET | `/mp/message/unread-count` | 要登录（已配） |
| POST | `/mp/message/{messageNo}/read` | 要登录（已配） |

</details>

<details><summary><code>cart</code> —— 4 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/cart` | 要登录（已配） |
| POST | `/mp/cart/add` | 要登录（已配） |
| POST | `/mp/cart/remove` | 要登录（已配） |
| POST | `/mp/cart/update` | 要登录（已配） |

</details>

<details><summary><code>community</code> —— 4 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/community` | 公开（端上声明不用登录） |
| GET | `/mp/community/nearby` | 公开（端上声明不用登录） |
| GET | `/mp/community/regions` | 公开（端上声明不用登录） |
| GET | `/mp/community/{communityNo}` | 公开（端上声明不用登录） |

</details>

<details><summary><code>coupon</code> —— 4 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/coupon` | 公开（端上声明不用登录） |
| POST | `/mp/coupon/best` | 要登录（已配） |
| GET | `/mp/coupon/mine` | 要登录（已配） |
| POST | `/mp/coupon/{couponNo}/receive` | 要登录（已配） |

</details>

<details><summary><code>favorite</code> —— 4 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/favorite/goods` | 要登录（已配） |
| POST | `/mp/favorite/goods/{goodsNo}` | 要登录（已配） |
| GET | `/mp/favorite/store` | 要登录（已配） |
| POST | `/mp/favorite/store/{merchantNo}` | 要登录（已配） |

</details>

<details><summary><code>invoice</code> —— 3 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/invoice/apply` | 要登录（已配） |
| GET | `/mp/invoice/mine` | 要登录（已配） |
| GET | `/mp/invoice/order/{orderNo}` | 要登录（已配） |

</details>

<details><summary><code>review</code> —— 3 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/review` | 公开（端上声明不用登录） |
| POST | `/mp/review` | 要登录（已配） |
| POST | `/mp/review/{reviewNo}/like` | 要登录（已配） |

</details>

<details><summary><code>ticket</code> —— 3 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/ticket` | 要登录（已配） |
| POST | `/mp/ticket` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/ticket/{ticketNo}` | 要登录（已配） |

</details>

<details><summary><code>track</code> —— 3 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/track` | 公开（端上声明不用登录） |
| GET | `/mp/track/mine` | 要登录（已配） |
| GET | `/mp/track/mini-link` | 公开（端上声明不用登录） |

</details>

<details><summary><code>entitlement-claims</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/entitlement-claims` | 未定（端上没有声明，写接口没法实测） |
| GET | `/mp/entitlement-claims/{intentNo}` | 要登录（已配） |

</details>

<details><summary><code>entitlement-service-orders</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/entitlement-service-orders` | 要登录（已配） |
| GET | `/mp/entitlement-service-orders/{serviceOrderNo}` | 要登录（已配） |

</details>

<details><summary><code>entitlement-usage</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/entitlement-usage` | 要登录（已配） |
| GET | `/mp/entitlement-usage/{usageNo}` | 要登录（已配） |

</details>

<details><summary><code>my-memberships</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/my-memberships` | 要登录（已配） |
| PUT | `/mp/my-memberships/{entityNo}/reach` | 要登录（已配） |

</details>

<details><summary><code>points</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/points/accounts` | 要登录（已配） |
| GET | `/mp/points/entries` | 要登录（已配） |

</details>

<details><summary><code>push-token</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/push-token` | 要登录（已配） |
| POST | `/mp/push-token/unregister` | 要登录（已配） |

</details>

<details><summary><code>search</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/search/hot` | 公开（实测匿名能进，端上没有声明） |
| GET | `/mp/search/suggest` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>topics</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/topics` | 公开（实测匿名能进，端上没有声明） |
| GET | `/mp/topics/{topicNo}/goods` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>wx</code> —— 2 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/wx/callback` | 公开（实测匿名能进，端上没有声明） |
| POST | `/mp/wx/callback` | 未定（端上没有声明，写接口没法实测） |

</details>

<details><summary><code>attribution</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/attribution/report` | 未定（端上没有声明，写接口没法实测） |

</details>

<details><summary><code>category</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/category/tree` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>config</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/config/bootstrap` | 公开（端上声明不用登录） |

</details>

<details><summary><code>entitlement-quotes</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/entitlement-quotes` | 未定（端上没有声明，写接口没法实测） |

</details>

<details><summary><code>fission</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/fission` | 要登录（已配） |

</details>

<details><summary><code>help</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/help/faq` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>location</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/location/resolve` | 公开（端上声明不用登录） |

</details>

<details><summary><code>member-code</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/member-code` | 要登录（已配） |

</details>

<details><summary><code>member-reach</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/member-reach/{reachNo}/opened` | 要登录（已配） |

</details>

<details><summary><code>my-coupons</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/my-coupons` | 要登录（已配） |

</details>

<details><summary><code>orders</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/orders/{orderNo}/entitlement-authorizations` | 要登录（已配） |

</details>

<details><summary><code>pickup</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/pickup/{pickupNo}` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>place</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/place/search` | 公开（端上声明不用登录） |

</details>

<details><summary><code>question</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/question` | 要登录（已配） |

</details>

<details><summary><code>regions</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/regions` | 公开（端上声明不用登录） |

</details>

<details><summary><code>risk</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/risk/appeal` | 未定（端上没有声明，写接口没法实测） |

</details>

<details><summary><code>stores</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| GET | `/mp/stores/{storeNo}/appointment-slots` | 公开（实测匿名能进，端上没有声明） |

</details>

<details><summary><code>upload</code> —— 1 条</summary>

| 方法 | 地址 | 身份 |
|---|---|---|
| POST | `/mp/upload/image` | 要登录（已配） |

</details>

## 三、一次性配对清单

| 端 | 这一次可以配的 | 配成什么 | 状态 |
|---|---:|---|---|
| B 端 | 72 | 见 1.5「已配」逐条的规则 | **已一次配进库**（2026-11-19），抽查与业务自己的判断一致 |
| B 端 | 10 | — | 待确认：属主判定或没读完的协调器 |
| C 端 | 106 | 要登录 | 已配 |
| C 端 | 15 | 要登录或公开 | 2.2 的写接口，逐条定 |

**配的方式**：调 `POST /ops/api-resources/{id}/rule`（超管，带审计），不改任何业务代码。配错了撤销同一个接口传空即可；业务里原有的检查一直在，库里配错最坏是网关多拦或少拦，不会绕过业务。
写接口的管理空间号现在在请求体里，网关读不到——这些规则配上之后，要等商户 App 带上 `X-Management-No` 请求头才开始生效，在那之前网关对它们不判。

## 怎么重新生成

本文件由一次性的脚本从线上数据生成（接口资源、`/biz/roles`、`/biz/role-perms`），不是仓库里的生成物。入口或规则变了之后要重新整理。
