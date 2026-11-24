# TDD-发票服务-API文档

状态：草稿 · 设计稿未实现
> 由 `python3 scripts/gen-invoice-service-docs.py` 生成 · 真源 `docs/api/invoice-service.openapi.json` · **勿手改**
> 设计依据：[TDD-发票服务-总体设计](./TDD-发票服务-总体设计.md) §13 · 表结构：[TDD-发票服务-数据库表结构说明](./TDD-发票服务-数据库表结构说明.md)

## 一、总览

商家自填（managedBy=MERCHANT，B 端）与平台代维护（managedBy=PLATFORM，运营端）两种凭据维护方式，不设审核。设计见 docs/technical/TDD-发票服务-总体设计.md §13。开票、作废、折让等票据接口见 docs/api/invoice.openapi.json。

通用约定：

- 响应统一包 `{code, message, data}`，下文「响应」指 `data`；失败时 `code` 为错误码。
- B 端（`/biz/*`）主体取登录上下文，带 `X-Store-No`，入参不收主体号；运营端显式传 `entityNo`。
- 写接口支持 `Idempotency-Key` 头。
- 凭据秘密字段只写不读：响应里只有 `credential.configured / version / fingerprint / masks`。
- `editable` 由后端按 `managedBy` 与调用方算好：B 端只能改 `MERCHANT` 账户，运营端只能改 `PLATFORM` 账户（运营端另可暂停任意账户、接管 / 交还）。

## 二、接口清单（30 个）

| # | 分组 | 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|---|---|
| 1 | B端 · 发票设置 | GET | `/biz/invoice-setup/providers` | 可用供应商与凭据字段 | `biz:invoice:setup` |
| 2 | B端 · 发票设置 | GET | `/biz/invoice-setup/sellers` | 本主体各地区营业人 | `biz:invoice:setup` |
| 3 | B端 · 发票设置 | PUT | `/biz/invoice-setup/sellers/{regionCode}` | 建 / 改营业人 | `biz:invoice:setup` |
| 4 | B端 · 发票设置 | GET | `/biz/invoice-setup/accounts` | 本主体全部账户 | `biz:invoice:setup` |
| 5 | B端 · 发票设置 | POST | `/biz/invoice-setup/accounts` | 新建账户（商家自填） | `biz:invoice:setup` |
| 6 | B端 · 发票设置 | GET | `/biz/invoice-setup/accounts/{accountNo}` | 账户详情 | `biz:invoice:setup` |
| 7 | B端 · 发票设置 | PUT | `/biz/invoice-setup/accounts/{accountNo}/credentials` | 轮换凭据 | `biz:invoice:setup` |
| 8 | B端 · 发票设置 | POST | `/biz/invoice-setup/accounts/{accountNo}/verifications` | 重新核验 | `biz:invoice:setup` |
| 9 | B端 · 发票设置 | PUT | `/biz/invoice-setup/accounts/{accountNo}/status` | 启用 / 暂停 / 关闭 | `biz:invoice:setup` |
| 10 | B端 · 发票设置 | GET | `/biz/invoice-setup/accounts/{accountNo}/events` | 账户事件 | `biz:invoice:setup` |
| 11 | B端 · 发票设置 | POST | `/biz/invoice-setup/accounts/{accountNo}/ranges` | 向供应商领号段 | `biz:invoice:setup` |
| 12 | B端 · 发票设置 | GET | `/biz/invoice-setup/bindings` | 门店 → 账户绑定 | `biz:invoice:setup` |
| 13 | B端 · 发票设置 | PUT | `/biz/invoice-setup/bindings/{storeNo}` | 改门店绑定 | `biz:invoice:setup` |
| 14 | 运营端 · 供应商 | GET | `/invoice-ops/v1/providers` | 供应商列表 | `ops:invoice:provider` |
| 15 | 运营端 · 供应商 | PUT | `/invoice-ops/v1/providers/{providerCode}` | 改供应商开关与地址 | `ops:invoice:provider` |
| 16 | 运营端 · 供应商 | PUT | `/invoice-ops/v1/providers/{providerCode}/regions/{regionCode}` | 开通 / 关闭某地区 | `ops:invoice:provider` |
| 17 | 运营端 · 账户与营业人 | GET | `/invoice-ops/v1/sellers` | 营业人查询 | `ops:invoice:account` |
| 18 | 运营端 · 账户与营业人 | PUT | `/invoice-ops/v1/sellers/{entityNo}/{regionCode}` | 代建 / 代改营业人 | `ops:invoice:account` |
| 19 | 运营端 · 账户与营业人 | GET | `/invoice-ops/v1/accounts` | 账户查询 | `ops:invoice:account` |
| 20 | 运营端 · 账户与营业人 | POST | `/invoice-ops/v1/accounts` | 代建账户（平台代维护） | `ops:invoice:account` |
| 21 | 运营端 · 账户与营业人 | POST | `/invoice-ops/v1/accounts/imports` | 批量导入账户 | `ops:invoice:account` |
| 22 | 运营端 · 账户与营业人 | GET | `/invoice-ops/v1/accounts/{accountNo}` | 账户详情 | `ops:invoice:account` |
| 23 | 运营端 · 账户与营业人 | PUT | `/invoice-ops/v1/accounts/{accountNo}/credentials` | 轮换凭据 | `ops:invoice:account` |
| 24 | 运营端 · 账户与营业人 | POST | `/invoice-ops/v1/accounts/{accountNo}/verifications` | 核验 | `ops:invoice:account` |
| 25 | 运营端 · 账户与营业人 | PUT | `/invoice-ops/v1/accounts/{accountNo}/status` | 改状态 | `ops:invoice:account` |
| 26 | 运营端 · 账户与营业人 | PUT | `/invoice-ops/v1/accounts/{accountNo}/management` | 接管 / 交还 | `ops:invoice:account` |
| 27 | 运营端 · 账户与营业人 | GET | `/invoice-ops/v1/accounts/{accountNo}/events` | 账户事件 | `ops:invoice:account` |
| 28 | 运营端 · 账户与营业人 | POST | `/invoice-ops/v1/accounts/{accountNo}/ranges` | 代领号段 | `ops:invoice:account` |
| 29 | 运营端 · 账户与营业人 | GET | `/invoice-ops/v1/bindings` | 门店绑定查询 | `ops:invoice:account` |
| 30 | 运营端 · 账户与营业人 | PUT | `/invoice-ops/v1/bindings/{entityNo}/{storeNo}` | 代改门店绑定 | `ops:invoice:account` |

## 三、逐个接口

### B端 · 发票设置

#### 1. GET `/biz/invoice-setup/providers` · 可用供应商与凭据字段

权限：`biz:invoice:setup`

本地区可用（地区已开通、能力已启用）的供应商，带录入表单字段定义

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `regionCode` | query | `string` | 是 | 地区码，如 TW |

请求体：—　响应：`ProviderVO`[]

#### 2. GET `/biz/invoice-setup/sellers` · 本主体各地区营业人

权限：`biz:invoice:setup`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |

请求体：—　响应：`SellerVO`[]

#### 3. PUT `/biz/invoice-setup/sellers/{regionCode}` · 建 / 改营业人

权限：`biz:invoice:setup`

不存在则建；QR 金钥只写不读，营业人下有平台代维护账户时由运营维护

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `regionCode` | path | `string` | 是 | 地区码 |

请求体：`SellerReq`　响应：`SellerVO`

错误码：`26004` INVOICE_BUYER_INVALID、`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26010` INVOICE_KEY_UNAVAILABLE

#### 4. GET `/biz/invoice-setup/accounts` · 本主体全部账户

权限：`biz:invoice:setup`

含平台代维护的账户（editable=false，只读）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `regionCode` | query | `string` |  | 地区码，可空 |
| `status` | query | `string` |  | 状态，可空 |

请求体：—　响应：`AccountVO`[]

#### 5. POST `/biz/invoice-setup/accounts` · 新建账户（商家自填）

权限：`biz:invoice:setup`

managedBy=MERCHANT。保存首版凭据为 PENDING 后立即核验：通过 → 账户 ACTIVE、凭据 CURRENT，营业人无默认绑定时自动绑定 '*'；不通过 → 账户保持 ONBOARDING，返回 200 + 账户视图（credential.pending.status=FAILED）。供应商不支持核验时直接 ACTIVE。不设审核

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |

请求体：`AccountCreateReq`　响应：`AccountVO`

错误码：`26001` INVOICE_REGIME_NOT_ENABLED、`26013` INVOICE_CREDENTIAL_INVALID {0}、`26014` INVOICE_ACCOUNT_DUPLICATE、`26011` INVOICE_PROVIDER_REJECTED {0}、`26010` INVOICE_KEY_UNAVAILABLE

#### 6. GET `/biz/invoice-setup/accounts/{accountNo}` · 账户详情

权限：`biz:invoice:setup`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：—　响应：`AccountVO`

错误码：`26005` INVOICE_NOT_FOUND

#### 7. PUT `/biz/invoice-setup/accounts/{accountNo}/credentials` · 轮换凭据

权限：`biz:invoice:setup`

新凭据存为 PENDING → 自动核验 → 通过则切为 CURRENT、旧版本 RETIRED；失败则旧版本继续服务。仅 managedBy=MERCHANT

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`CredentialRotateReq`　响应：`AccountVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26013` INVOICE_CREDENTIAL_INVALID {0}、`26011` INVOICE_PROVIDER_REJECTED {0}、`26010` INVOICE_KEY_UNAVAILABLE

#### 8. POST `/biz/invoice-setup/accounts/{accountNo}/verifications` · 重新核验

权限：`biz:invoice:setup`

用当前凭据核验；鲸跃会同步回传 QR 金钥。仅 managedBy=MERCHANT

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：—　响应：`VerificationVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26011` INVOICE_PROVIDER_REJECTED {0}、`26008` INVOICE_STATE_INVALID

#### 9. PUT `/biz/invoice-setup/accounts/{accountNo}/status` · 启用 / 暂停 / 关闭

权限：`biz:invoice:setup`

ACTIVE 需凭据已核验通过（或供应商无核验能力）；CLOSED 前须无绑定、无未完成上传、无未知态票。仅 managedBy=MERCHANT

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`AccountStatusReq`　响应：`AccountVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID、`26014` INVOICE_ACCOUNT_DUPLICATE、`26015` INVOICE_ACCOUNT_IN_USE

#### 10. GET `/biz/invoice-setup/accounts/{accountNo}/events` · 账户事件

权限：`biz:invoice:setup`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `accountNo` | path | `string` | 是 | 账户业务号 |
| `page` | query | `integer` |  | 页码，从 1 起 |
| `size` | query | `integer` |  | 每页条数，≤100 |

请求体：—　响应：`AccountEventPage`

#### 11. POST `/biz/invoice-setup/accounts/{accountNo}/ranges` · 向供应商领号段

权限：`biz:invoice:setup`

仅号段类供应商（鲸跃）；同一段重复取回只入库一次。仅 managedBy=MERCHANT

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`RangeFetchReq`　响应：`NumberRangeVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID、`26011` INVOICE_PROVIDER_REJECTED {0}、`26009` INVOICE_RANGE_INVALID

#### 12. GET `/biz/invoice-setup/bindings` · 门店 → 账户绑定

权限：`biz:invoice:setup`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |

请求体：—　响应：`BindingVO`[]

#### 13. PUT `/biz/invoice-setup/bindings/{storeNo}` · 改门店绑定

权限：`biz:invoice:setup`

storeNo='*' 为营业人级默认；只影响之后的新票，已开的票沿原账户作废 / 折让。目标账户须为 MERCHANT 且 ACTIVE

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `X-Store-No` | header | `string` | 是 | 当前门店；主体取登录上下文，入参不收主体号 |
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `storeNo` | path | `string` | 是 | 门店号或 * |

请求体：`BindingReq`　响应：`BindingVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID

### 运营端 · 供应商

#### 14. GET `/invoice-ops/v1/providers` · 供应商列表

权限：`ops:invoice:provider`

含地区开通、能力、凭据字段定义

请求体：—　响应：`ProviderVO`[]

#### 15. PUT `/invoice-ops/v1/providers/{providerCode}` · 改供应商开关与地址

权限：`ops:invoice:provider`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `providerCode` | path | `string` | 是 | 供应商码 |

请求体：`ProviderUpdateReq`　响应：`ProviderVO`

#### 16. PUT `/invoice-ops/v1/providers/{providerCode}/regions/{regionCode}` · 开通 / 关闭某地区

权限：`ops:invoice:provider`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `providerCode` | path | `string` | 是 | 供应商码 |
| `regionCode` | path | `string` | 是 | 地区码 |

请求体：`ProviderRegionReq`　响应：`ProviderVO`

### 运营端 · 账户与营业人

#### 17. GET `/invoice-ops/v1/sellers` · 营业人查询

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `entityNo` | query | `string` |  | 主体号 |
| `regionCode` | query | `string` |  | 地区码 |

请求体：—　响应：`SellerVO`[]

#### 18. PUT `/invoice-ops/v1/sellers/{entityNo}/{regionCode}` · 代建 / 代改营业人

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `entityNo` | path | `string` | 是 | 主体号 |
| `regionCode` | path | `string` | 是 | 地区码 |

请求体：`SellerReq`　响应：`SellerVO`

错误码：`26004` INVOICE_BUYER_INVALID、`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26010` INVOICE_KEY_UNAVAILABLE

#### 19. GET `/invoice-ops/v1/accounts` · 账户查询

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `entityNo` | query | `string` |  | 主体号 |
| `providerCode` | query | `string` |  | 供应商 |
| `managedBy` | query | `string` |  | MERCHANT / PLATFORM |
| `status` | query | `string` |  | 状态 |
| `page` | query | `integer` |  | 页码 |
| `size` | query | `integer` |  | 每页 ≤100 |

请求体：—　响应：`AccountPage`

#### 20. POST `/invoice-ops/v1/accounts` · 代建账户（平台代维护）

权限：`ops:invoice:account`

managedBy=PLATFORM；流程同 B 端新建：首版凭据 PENDING → 核验通过即 ACTIVE，不设审核

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |

请求体：`OpsAccountCreateReq`　响应：`AccountVO`

错误码：`26001` INVOICE_REGIME_NOT_ENABLED、`26013` INVOICE_CREDENTIAL_INVALID {0}、`26014` INVOICE_ACCOUNT_DUPLICATE、`26011` INVOICE_PROVIDER_REJECTED {0}、`26010` INVOICE_KEY_UNAVAILABLE

#### 21. POST `/invoice-ops/v1/accounts/imports` · 批量导入账户

权限：`ops:invoice:account`

存量迁移（如 UME 鲸跃账号）；逐行独立处理与返回，一行失败不影响其它行；managedBy=PLATFORM

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |

请求体：`AccountImportReq`　响应：`AccountImportResultVO`

#### 22. GET `/invoice-ops/v1/accounts/{accountNo}` · 账户详情

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：—　响应：`AccountVO`

错误码：`26005` INVOICE_NOT_FOUND

#### 23. PUT `/invoice-ops/v1/accounts/{accountNo}/credentials` · 轮换凭据

权限：`ops:invoice:account`

仅 managedBy=PLATFORM；规则同 B 端

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`CredentialRotateReq`　响应：`AccountVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26013` INVOICE_CREDENTIAL_INVALID {0}、`26011` INVOICE_PROVIDER_REJECTED {0}、`26010` INVOICE_KEY_UNAVAILABLE

#### 24. POST `/invoice-ops/v1/accounts/{accountNo}/verifications` · 核验

权限：`ops:invoice:account`

任一维护方的账户都可触发（不改凭据）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：—　响应：`VerificationVO`

错误码：`26011` INVOICE_PROVIDER_REJECTED {0}、`26008` INVOICE_STATE_INVALID

#### 25. PUT `/invoice-ops/v1/accounts/{accountNo}/status` · 改状态

权限：`ops:invoice:account`

PLATFORM 账户可设任意状态；MERCHANT 账户只允许设 SUSPENDED（止损）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`AccountStatusReq`　响应：`AccountVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID、`26014` INVOICE_ACCOUNT_DUPLICATE、`26015` INVOICE_ACCOUNT_IN_USE

#### 26. PUT `/invoice-ops/v1/accounts/{accountNo}/management` · 接管 / 交还

权限：`ops:invoice:account`

只改 managedBy 并记事件；凭据、状态、绑定、号段都不动

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`ManagementReq`　响应：`AccountVO`

#### 27. GET `/invoice-ops/v1/accounts/{accountNo}/events` · 账户事件

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `accountNo` | path | `string` | 是 | 账户业务号 |
| `page` | query | `integer` |  | 页码 |
| `size` | query | `integer` |  | 每页 ≤100 |

请求体：—　响应：`AccountEventPage`

#### 28. POST `/invoice-ops/v1/accounts/{accountNo}/ranges` · 代领号段

权限：`ops:invoice:account`

仅 managedBy=PLATFORM 且号段类供应商

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `accountNo` | path | `string` | 是 | 账户业务号 |

请求体：`RangeFetchReq`　响应：`NumberRangeVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID、`26011` INVOICE_PROVIDER_REJECTED {0}、`26009` INVOICE_RANGE_INVALID

#### 29. GET `/invoice-ops/v1/bindings` · 门店绑定查询

权限：`ops:invoice:account`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `entityNo` | query | `string` | 是 | 主体号 |

请求体：—　响应：`BindingVO`[]

#### 30. PUT `/invoice-ops/v1/bindings/{entityNo}/{storeNo}` · 代改门店绑定

权限：`ops:invoice:account`

目标账户须为 PLATFORM 且 ACTIVE；只影响之后的新票

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `Idempotency-Key` | header | `string` |  | 幂等键，同键重复提交返回首次结果 |
| `entityNo` | path | `string` | 是 | 主体号 |
| `storeNo` | path | `string` | 是 | 门店号或 * |

请求体：`BindingReq`　响应：`BindingVO`

错误码：`26012` INVOICE_ACCOUNT_MANAGED_ELSEWHERE、`26008` INVOICE_STATE_INVALID

## 四、数据结构

#### CredentialFieldVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fieldCode` | `string` |  | 字段码，如 office_id / pos_no / hash_key |
| `labelKey` | `string` |  | 多语文案键 |
| `secret` | `boolean` |  | true = 只写不读，回显为掩码 |
| `accountCode` | `boolean` |  | true = 该字段即账户代号 providerAccountCode |
| `required` | `boolean` |  |  |
| `pattern` | `string` |  | 格式正则，可空 |
| `maxLength` | `integer` |  |  |
| `sortNo` | `integer` |  |  |

#### ProviderVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `providerCode` | `string` |  | CETUSTEK / ECPAY / TURNKEY / STUB |
| `name` | `string` |  |  |
| `providerType` | `string` |  | VALUE_ADDED_CENTER / GOV_DIRECT / ASP / TEST |
| `status` | `string` |  | ACTIVE / DISABLED |
| `regions` | `string`[] |  | 已开通地区码 |
| `products` | `ProviderProductVO`[] |  |  |
| `credentialFields` | `CredentialFieldVO`[] |  | 录入表单字段，按 sortNo 排序 |

#### ProviderProductVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `regionCode` | `string` |  |  |
| `docType` | `string` |  | B2C / B2B |
| `numberingMode` | `string` |  | LOCAL_RANGE / PROVIDER_RANGE / PROVIDER_ONLINE / LOCAL_SEQUENCE |
| `reportMode` | `string` |  | ASYNC_UPLOAD / SYNC_ON_ISSUE / FILE_DROP |
| `supportsVoid` | `boolean` |  |  |
| `supportsAllowance` | `boolean` |  |  |
| `supportsAllowanceVoid` | `boolean` |  |  |
| `supportsQuery` | `boolean` |  |  |
| `supportsVerify` | `boolean` |  |  |
| `printMode` | `string` |  | LOCAL / PROVIDER_URL |
| `enabled` | `boolean` |  |  |

#### ProviderUpdateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | `string` |  | ACTIVE / DISABLED（enum=ACTIVE/DISABLED） |
| `sandboxEndpoint` | `string` |  | （maxLength=255） |
| `productionEndpoint` | `string` |  | （maxLength=255） |

#### ProviderRegionReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | `string` | 是 | （enum=ACTIVE/DISABLED） |

#### SellerReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taxId` | `string` | 是 | 统编 / TRN，按地区税制校验（maxLength=32） |
| `legalName` | `string` | 是 | （maxLength=128） |
| `address` | `string` |  | （maxLength=256） |
| `autoIssue` | `boolean` |  | 付款成功后自动开立；不传 = 关 |
| `memberCarrierType` | `string` |  | 会员载具类别号；不传 = 清空（maxLength=16） |
| `qrAesKeyHex` | `string` |  | QR 加密金钥（32 位十六进制），只写不读；不传 = 不改（pattern=^[0-9A-Fa-f]{32}$） |

#### SellerVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sellerNo` | `string` |  |  |
| `entityNo` | `string` |  |  |
| `regionCode` | `string` |  |  |
| `regimeCode` | `string` |  |  |
| `taxId` | `string` |  |  |
| `legalName` | `string` |  |  |
| `address` | `string` |  |  |
| `autoIssue` | `boolean` |  |  |
| `memberCarrierType` | `string` |  |  |
| `qrKey` | `object` |  | QR 金钥只回状态 |
| `status` | `string` |  | ACTIVE / SUSPENDED |
| `qrKeyEditable` | `boolean` |  | 当前调用方能否改 QR 金钥（营业人下有平台代维护账户时只能运营改） |

#### CredentialInput

凭据键值：键为 credentialFields 的 fieldCode；秘密字段只写不读。缺必填 / 多余键 / 格式不符 → 26013

键值对象，值类型 `string`。

#### AccountCreateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `regionCode` | `string` | 是 | 地区，如 TW |
| `providerCode` | `string` | 是 |  |
| `environment` | `string` | 是 | （enum=SANDBOX/PRODUCTION） |
| `credentials` | `CredentialInput` | 是 |  |

#### OpsAccountCreateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `entityNo` | `string` | 是 | 主体业务号 |
| `regionCode` | `string` | 是 |  |
| `providerCode` | `string` | 是 |  |
| `environment` | `string` | 是 | （enum=SANDBOX/PRODUCTION） |
| `credentials` | `CredentialInput` | 是 |  |

#### CredentialRotateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `credentials` | `CredentialInput` | 是 |  |

#### AccountStatusReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | `string` | 是 | （enum=ACTIVE/SUSPENDED/CLOSED） |
| `reason` | `string` |  | （maxLength=256） |

#### ManagementReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `managedBy` | `string` | 是 | （enum=MERCHANT/PLATFORM） |
| `reason` | `string` | 是 | （maxLength=256） |

#### CredentialStateVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `configured` | `boolean` |  | 有 CURRENT 版本 |
| `version` | `integer` |  | CURRENT 版本号 |
| `fingerprint` | `string` |  |  |
| `masks` | `object` |  | 字段码 → 掩码 |
| `pending` | `object` |  | 最近一次提交但未生效的版本；没有则为 null |

#### AccountVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accountNo` | `string` |  |  |
| `entityNo` | `string` |  |  |
| `sellerNo` | `string` |  |  |
| `regionCode` | `string` |  |  |
| `providerCode` | `string` |  |  |
| `environment` | `string` |  |  |
| `providerAccountCode` | `string` |  | 鲸跃 officeid / 绿界 MerchantID |
| `managedBy` | `string` |  | MERCHANT / PLATFORM |
| `status` | `string` |  | ONBOARDING / ACTIVE / ISSUE_STOPPED / SUSPENDED / CLOSED |
| `editable` | `boolean` |  | 当前调用方能否改凭据 / 状态 / 绑定（后端按 managedBy 算好） |
| `credential` | `CredentialStateVO` |  |  |
| `verifiedAt` | `string` |  | （format=date-time） |
| `verifiedTaxId` | `string` |  |  |
| `lastVerifyError` | `string` |  |  |
| `boundStores` | `string`[] |  | 绑定的门店，'*' = 营业人级默认 |
| `createdVia` | `string` |  | BIZ / OPS |
| `createdAt` | `string` |  | （format=date-time） |

#### AccountPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `total` | `integer` |  |  |
| `page` | `integer` |  |  |
| `size` | `integer` |  |  |
| `list` | `AccountVO`[] |  |  |

#### VerificationVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `passed` | `boolean` |  |  |
| `taxId` | `string` |  | 供应商侧统编 |
| `name` | `string` |  | 供应商侧营业人名称 |
| `qrKeyStored` | `boolean` |  | 供应商回传的 QR 金钥已存入营业人 |
| `errorCode` | `string` |  | 未通过时的供应商原文代码 |
| `account` | `AccountVO` |  |  |

#### BindingVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `storeNo` | `string` |  | '*' = 营业人级默认 |
| `regionCode` | `string` |  |  |
| `accountNo` | `string` |  |  |
| `providerCode` | `string` |  |  |
| `effectiveFrom` | `string` |  | （format=date-time） |
| `editable` | `boolean` |  |  |

#### BindingReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accountNo` | `string` | 是 |  |
| `regionCode` | `string` | 是 |  |

#### AccountEventVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `eventType` | `string` |  |  |
| `operatorType` | `string` |  | BIZ / OPS / SYSTEM |
| `operatorNo` | `string` |  |  |
| `detail` | `object` |  | 掩码后的差异，不含凭据 |
| `createdAt` | `string` |  | （format=date-time） |

#### AccountEventPage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `total` | `integer` |  |  |
| `page` | `integer` |  |  |
| `size` | `integer` |  |  |
| `list` | `AccountEventVO`[] |  |  |

#### RangeFetchReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `periodCode` | `string` |  | 期别（5 位，如 11512）；不传 = 当期（pattern=^\d{5}$） |

#### NumberRangeVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `rangeNo` | `string` |  |  |
| `accountNo` | `string` |  |  |
| `periodCode` | `string` |  |  |
| `track` | `string` |  |  |
| `startNo` | `integer` |  |  |
| `endNo` | `integer` |  |  |
| `nextNo` | `integer` |  |  |
| `remaining` | `integer` |  |  |
| `allocationOwner` | `string` |  | MANUAL / PROVIDER |
| `status` | `string` |  | ACTIVE / EXHAUSTED |

#### AccountImportReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `rows` | `OpsAccountCreateReq`[] | 是 | 每行等同一次 POST /invoice-ops/v1/accounts；最多 200 行 |

#### AccountImportResultVO

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `rows` | `object`[] |  |  |

## 五、错误码

| 码 | 键 | 何时 |
|---|---|---|
| 26001 | `INVOICE_REGIME_NOT_ENABLED` | 地区未开通或营业人不存在 |
| 26004 | `INVOICE_BUYER_INVALID` | 统编 / TRN 格式不符（沿用码） |
| 26005 | `INVOICE_NOT_FOUND` | 账户不存在或不属于本主体 |
| 26008 | `INVOICE_STATE_INVALID` | 当前状态不允许（如未核验就启用、非号段类领号段） |
| 26009 | `INVOICE_RANGE_INVALID` | 号段与已有号段重叠或格式不符 |
| 26010 | `INVOICE_KEY_UNAVAILABLE` | 密钥服务不可用 |
| 26011 | `INVOICE_PROVIDER_REJECTED {0}` | 供应商拒绝或不可达，{0} 为供应商原文代码 |
| 26012 | `INVOICE_ACCOUNT_MANAGED_ELSEWHERE` | 账户不归当前调用方维护（新增） |
| 26013 | `INVOICE_CREDENTIAL_INVALID {0}` | 凭据字段缺失 / 多余 / 格式不符，{0} 为字段码（新增） |
| 26014 | `INVOICE_ACCOUNT_DUPLICATE` | 同营业人同供应商同环境已有启用账户（新增） |
| 26015 | `INVOICE_ACCOUNT_IN_USE` | 账户仍有绑定 / 未完成上传 / 未知态票，不能关闭（新增） |
