# 运营管理 API · 模块与角色清单

> 生成物。维护路由准入、Controller 或角色基线后运行 `node scripts/gen-ops-key-api.mjs`。

角色来自内置授权基线。实际以员工当前角色、已配置功能点和数据域为准。任一员工可同时拥有多个角色。

使用 [OpenAPI](./ops-management.openapi.json) 导入工具，参考[密钥分发与调用](./运营管理API-调用指南.md)。

## 身份与密钥

| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |
|---|---|---|---|---|
| 查询当前运营员工身份 | `GET /ops/auth/me` | 只读／管理，按员工数据域 | 已认证 | 全部运营角色 |
| 查看本人的 API 密钥 | `GET /ops/staffs/me/api-keys` | 运营会话 | 已认证 | 全部运营角色 |
| 自助申请并绑定本机 API 密钥 | `POST /ops/staffs/me/api-keys` | 运营会话 | 已认证 | 全部运营角色 |
| 撤销本人的 API 密钥 | `POST /ops/staffs/me/api-keys/{keyNo}/revoke` | 运营会话 | 已认证 | 全部运营角色 |
| 查询员工密钥元数据 | `GET /ops/staffs/{staffNo}/api-keys` | 运营会话 | iam:staff:api-key-manage | SUPER_ADMIN |
| 为运营员工签发密钥 | `POST /ops/staffs/{staffNo}/api-keys` | 运营会话 | iam:staff:api-key-manage | SUPER_ADMIN |
| 撤销员工密钥 | `POST /ops/staffs/{staffNo}/api-keys/{keyNo}/revoke` | 运营会话 | iam:staff:api-key-manage | SUPER_ADMIN |

## 门店

| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |
|---|---|---|---|---|
| 跨主体门店检索 | `GET /ops/stores` | 只读／管理，按员工数据域 | merchant:merchant:read | BD、GOODS_OPS、SUPER_ADMIN |
| 门店档案详情：门面 + 配送规则 + 经营模式 + 收款商户号 | `GET /ops/stores/{storeNo}` | 只读／管理，按员工数据域 | merchant:merchant:read | BD、GOODS_OPS、SUPER_ADMIN |
| 解除门店强制下线，恢复被平台压下的货架行 | `POST /ops/stores/{storeNo}/restore` | 管理，全量数据域 | merchant:merchant:ban | BD、SUPER_ADMIN |

## 商户

| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |
|---|---|---|---|---|
| 检索商户 | `GET /ops/merchants` | 只读／管理，按员工数据域 | merchant:merchant:read | BD、GOODS_OPS、SUPER_ADMIN |
| 查询商户详情 | `GET /ops/merchants/{merchantNo}` | 只读／管理，按员工数据域 | merchant:merchant:read | BD、GOODS_OPS、SUPER_ADMIN |
| 调整商户经营状态 | `POST /ops/merchants/{merchantNo}/status` | 管理，全量数据域 | merchant:merchant:ban | BD、SUPER_ADMIN |

## 渠道

| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |
|---|---|---|---|---|
| 商家履约配置（方案 v4，**只读**）：门店 × 送货方式矩阵 | `GET /ops/merchants/{merchantNo}/fulfillment` | 只读／管理，全量数据域 | merchant:merchant:read | BD、GOODS_OPS、SUPER_ADMIN |
| 锁定门店履约渠道 | `POST /ops/stores/{storeNo}/channels/{channel}/lock` | 管理，全量数据域 | merchant:fulfillment:update | BD、SUPER_ADMIN |
| 解锁门店履约渠道 | `POST /ops/stores/{storeNo}/channels/{channel}/unlock` | 管理，全量数据域 | merchant:fulfillment:update | BD、SUPER_ADMIN |
| 物流渠道总览（O4） | `GET /ops/logistics/channels` | 只读／管理，全量数据域 | fulfillment:logistics:read | COMMUNITY_OPS、SUPER_ADMIN |
| 支付通道设置 + 每个通道的费率版本 | `GET /ops/settle/pay-channels` | 只读／管理，全量数据域 | finance:rate:read | FINANCE、SUPER_ADMIN |

## 行业与经营范围

| 操作 | HTTP 端点 | 凭据与范围 | 权限码 | 默认角色 |
|---|---|---|---|---|
| 查询行业主数据和在用商家数 | `GET /ops/industries` | 只读／管理，全量数据域 | system:industry:read | COMMUNITY_OPS、SUPER_ADMIN |
| 查询经营范围档位及在用商家数 | `GET /ops/service-scopes` | 只读／管理，全量数据域 | system:industry:read | COMMUNITY_OPS、SUPER_ADMIN |
| 调整行业启用状态 | `POST /ops/industries/{industry}/enabled` | 管理，全量数据域 | system:industry:update | COMMUNITY_OPS、SUPER_ADMIN |
| 调整经营范围档位 | `POST /ops/service-scopes/{scope}/enabled` | 管理，全量数据域 | system:industry:update | COMMUNITY_OPS、SUPER_ADMIN |

## 按内置角色检索

| 角色 | 可用操作 ID |
|---|---|
| `ANALYST` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `AUDITOR` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `BD` | `me`、`listStores`、`getStore`、`restoreStore`、`listMerchants`、`getMerchant`、`setMerchantStatus`、`merchantFulfillment`、`lockChannel`、`unlockChannel`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `CAMPAIGN_OPS` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `COMMUNITY_OPS` | `me`、`listLogisticsChannels`、`listIndustries`、`listServiceScopes`、`setIndustryEnabled`、`setServiceScopeEnabled`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `ELEC_ADMIN` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `FINANCE` | `me`、`listPayChannels`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `GOODS_OPS` | `me`、`listStores`、`getStore`、`listMerchants`、`getMerchant`、`merchantFulfillment`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `RISK` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `SUPER_ADMIN` | `me`、`listStores`、`getStore`、`restoreStore`、`listMerchants`、`getMerchant`、`setMerchantStatus`、`merchantFulfillment`、`lockChannel`、`unlockChannel`、`listLogisticsChannels`、`listPayChannels`、`listIndustries`、`listServiceScopes`、`setIndustryEnabled`、`setServiceScopeEnabled`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey`、`listStaffApiKeys`、`issueStaffApiKey`、`revokeStaffApiKey` |
| `SUPPORT` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
| `TECH_OPS` | `me`、`listMyApiKeys`、`registerMyApiKey`、`revokeMyApiKey` |
