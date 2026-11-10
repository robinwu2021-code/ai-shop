# 基础能力 API 清单（固定部署提交）

范围、scope、状态及验收边界先读 [联调说明](./README.md)。164条方法/路径；存在路由不等于具备真实业务前置。

## N1认领

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/merchant/store-claims/by-request/{requestNo}` | byRequest | ACCOUNT_OWNER | 166_RECOVERY_CONDITIONAL |
| GET | `/biz/merchant/store-claims/{claimNo}` | get | ACCOUNT_OWNER | 166_RECOVERY_CONDITIONAL |
| POST | `/biz/merchant/store-claims/{claimNo}/resume` | resume | ACCOUNT_OWNER | 166_RECOVERY_CONDITIONAL |
| POST | `/biz/store/{storeNo}/entity` | claim | STORE | 166_NEW_WRITE_DISABLED |
| GET | `/biz/store/{storeNo}/entity/relink-check` | check | STORE | 166_ROUTE_DEPLOYED |

## N2渠道认领

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/pay/v2/channel-account-claims` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| POST | `/biz/pay/v2/channel-account-claims` | write | STORE_PAY | 166_ROUTE_DEPLOYED |
| GET | `/biz/pay/v2/channel-account-claims/by-request/{requestNo}` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/commands/{requestNo}` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | write | STORE_PAY | 166_ROUTE_DEPLOYED |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files/{fileNo}` | read | STORE_PAY | 166_ROUTE_DEPLOYED |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/submit` | write | STORE_PAY | 166_ROUTE_DEPLOYED |

## 主体与门店

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/entities` | myEntities | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/entity/{entityNo}` | detail | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/merchant/agreement/accept` | acceptAgreement | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/merchant/apply` | myApply | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/merchant/apply` | apply | STORE | COMPAT_PLATFORM_FORM |
| GET | `/biz/merchant/debt` | debt | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/merchant/display-name` | setDisplayName | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/merchant/pay-channel` | payChannels | STORE | LEGACY_NOT_TW_AUTHORITY |
| GET | `/biz/merchant/payment` | payments | STORE | LEGACY_NOT_TW_AUTHORITY |
| POST | `/biz/merchant/payment` | submitPayment | STORE | LEGACY_NOT_TW_AUTHORITY |
| POST | `/biz/merchant/payment/store/{storeNo}` | openStorePayment | STORE | LEGACY_NOT_TW_AUTHORITY |
| POST | `/biz/merchant/payment/{payChannel}/refresh` | refreshPayment | STORE | LEGACY_NOT_TW_AUTHORITY |
| GET | `/biz/merchant/profile` | profile | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/merchant/quick-start` | quickStart | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/qualifications` | qualifications | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/qualifications/save` | saveQualification | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/store` | store | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store` | saveStore | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/announcement` | saveAnnouncement | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/announcement/recent/remove` | dropRecentAnnouncement | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/create` | createStore | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/store/list` | storeList | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/scope-preview` | scopePreview | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/store/{storeNo}/categories` | storeCategories | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/categories` | saveStoreCategories | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/default` | setDefaultStore | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/payment` | setStorePayment | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/rename` | renameStore | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/slug` | setStoreSlug | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/store/{storeNo}/status` | setStoreStatus | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/mine` | myStores | ACCOUNT | 166_ROUTE_DEPLOYED |

## 员工与角色

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/role-perms` | rolePerms | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/role/{roleCode}` | updateRole | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/role/{roleCode}/delete` | deleteRole | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/roles` | roles | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/roles` | createRole | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/staff` | staffList | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/staff` | addStaff | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/staff/logs` | staffLogs | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/staff/{mchAccountNo}/status` | setStaffStatus | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/staff/{mchAccountNo}/store` | grantStore | STORE | 166_ROUTE_DEPLOYED |

## 平台资料

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/common/master-data` | masterData | PUBLIC | 166_ROUTE_DEPLOYED |

## 平台进件

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| POST | `/biz/merchant/onboarding` | submit | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/qualifications/recognize` | recognize | STORE | 166_ROUTE_DEPLOYED |

## 平台附件

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| POST | `/biz/upload/image` | upload | STORE | 166_ROUTE_DEPLOYED |

## 库存（既有宿主）

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| POST | `/biz/inventory/adjust` | adjust | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/balances` | balances | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/counts` | openCount | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/counts/{no}` | count | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| PUT | `/biz/inventory/counts/{no}/lines` | fillCount | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/counts/{no}/post` | postCount | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/counts/{no}/void` | voidCount | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/cross-store` | crossStore | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/documents` | documents | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/export` | export | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/inbounds` | createInbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| PUT | `/biz/inventory/inbounds/{no}` | updateInbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/inbounds/{no}/post` | postInbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/inbounds/{no}/void` | voidInbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/item-by-sku` | itemBySku | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/items/by-barcode` | byBarcode | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/items/{itemId}` | item | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/ledger` | ledger | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/outbounds` | createOutbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/outbounds/{no}/post` | postOutbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/outbounds/{no}/void` | voidOutbound | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/pickable` | pickable | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/report/monthly` | monthly | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/report/ranking` | ranking | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| PUT | `/biz/inventory/safety-stock` | safetyStock | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/summary` | summary | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/transfers` | createTransfer | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| GET | `/biz/inventory/transfers/{no}` | transfer | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/transfers/{no}/receive` | receive | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/transfers/{no}/ship` | ship | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |
| POST | `/biz/inventory/transfers/{no}/void` | voidTransfer | STORE | HOST_INVENTORY_EXISTING_NOT_NEW_STANDALONE |

## 支付来源

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/auth/session` | session | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/merchant/pay-service` | status | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/merchant/pay-service/apply` | apply | STORE | 166_ROUTE_DEPLOYED |

## 独立PAY进件15路由

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/pay/v2/capability-progress` | 能力进度 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/memberships` | 有效支付成员及动作 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings` | 申请分页 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| POST | `/biz/pay/v2/onboardings` | 建立申请 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}` | 单申请状态及驳回原因 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| POST | `/biz/pay/v2/onboardings/{no}/cancel` | 取消申请 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}/draft` | 脱敏草稿 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| PUT | `/biz/pay/v2/onboardings/{no}/draft` | 保存版本化草稿 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}/draft/commands/{requestNo}` | 原命令结果 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| POST | `/biz/pay/v2/onboardings/{no}/draft/confirm` | 商户确认交运营 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}/files` | 私有附件元数据 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| POST | `/biz/pay/v2/onboardings/{no}/files` | multipart上传 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}/files/{fileNo}` | 私有二进制读取 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| POST | `/biz/pay/v2/onboardings/{no}/newebpay-materials` | 敏感值封存 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |
| GET | `/biz/pay/v2/onboardings/{no}/readiness` | 账户与密钥交接进度 | PAY_MEMBER | 166_RELAY_DEPLOYED_CHANNEL_PRECONDITIONS |

## 经营基础

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/business-types` | types | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/business-types/{typeCode}/feature-matrix` | matrix | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/business-types/{typeCode}/plans` | plans | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/context` | context | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/merchant/store-setup` | storeSetup | ACCOUNT | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/context` | context | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/end-access` | status | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/stores/{storeNo}/end-sessions` | issue | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/entitlements` | entitlements | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/features` | features | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/pos/access` | pos | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/subscription` | subscription | STORE | 166_ROUTE_DEPLOYED |

## 账号身份

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| POST | `/biz/auth/email-otp/send` | sendEmailOtp | PUBLIC | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/identities` | list | PUBLIC | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/identities/enter` | enter | PUBLIC | 166_ROUTE_DEPLOYED |
| GET | `/biz/auth/identities/mine` | mine | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/login` | login | PUBLIC | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/logout` | logout | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/otp/send` | sendOtp | PUBLIC | 166_ROUTE_DEPLOYED |
| GET | `/biz/auth/password` | hasPassword | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/password` | setPassword | ACCOUNT | 166_ROUTE_DEPLOYED |
| POST | `/biz/auth/staff-login` | staffLogin | PUBLIC | 166_ROUTE_DEPLOYED |
| GET | `/biz/communities` | communities | ACCOUNT | 166_ROUTE_DEPLOYED |

## 连锁管理

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/management/brand-changes/{changeNo}` | change | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/management/brand-changes/{changeNo}/decisions` | decide | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/brands` | list | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/management/brands` | create | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/brands/{brandNo}` | detail | MANAGEMENT | 166_ROUTE_DEPLOYED |
| PUT | `/biz/management/brands/{brandNo}` | edit | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/brands/{brandNo}/audit-events` | audit | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/management/brands/{brandNo}/status-changes` | status | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/brands/{brandNo}/stores` | stores | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/context` | context | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/entities/{entityNo}/payment-merchants` | merchants | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/management/spaces` | create | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/spaces/{managementNo}/entities` | entities | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/management/spaces/{managementNo}/grants` | grant | MANAGEMENT | 166_ROUTE_DEPLOYED |
| DELETE | `/biz/management/spaces/{managementNo}/grants/{grantNo}` | revoke | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/spaces/{managementNo}/recipients` | recipients | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/spaces/{managementNo}/resources` | resources | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/management/spaces/{managementNo}/stores` | stores | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/stores/{storeNo}/brand-changes` | submit | MANAGEMENT | 166_ROUTE_DEPLOYED |
| POST | `/biz/stores/{storeNo}/brand-changes/check` | check | MANAGEMENT | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/operating-context` | context | MANAGEMENT | 166_ROUTE_DEPLOYED |

## 门店履约与选址

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| POST | `/biz/appointment-slots/{slotNo}/close` | closeSlot | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/geo/estates` | estates | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/geo/estates/counts` | estateCounts | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/geo/geocode` | geocode | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/geo/reverse` | reverse | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/geo/tips` | tips | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/appointment-slots` | slots | STORE | 166_ROUTE_DEPLOYED |
| POST | `/biz/stores/{storeNo}/appointment-slots` | openSlot | STORE | 166_ROUTE_DEPLOYED |
| GET | `/biz/stores/{storeNo}/fulfillment` | storeFulfillment | STORE | 166_ROUTE_DEPLOYED |
| PUT | `/biz/stores/{storeNo}/fulfillment` | saveStoreFulfillment | STORE | 166_ROUTE_DEPLOYED |

## 门店收款设置

| 方法 | 路径 | 方法/场景 | scope | 发布状态 |
|---|---|---|---|---|
| GET | `/biz/store/{storeNo}/pay-setting` | get | STORE | 166_ROUTE_DEPLOYED |
| PUT | `/biz/store/{storeNo}/pay-setting` | save | STORE | 166_ROUTE_DEPLOYED |
