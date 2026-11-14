# B 端注册至进件资料提交 · 标准 API 对接文档

文档版本：2026-11-04。后端合同基线：`6af2d200d`，分支 `codex/commerce-foundation`。本文件描述已实现的手机号注册登录、建店、证照申请与收款进件资料提交。配套 [OpenAPI 3.1 JSON](openapi-b-onboarding.json) 可直接导入 API 调试或文档工具；[既有真实 HTTP 示例](examples/b-foundation.json) 可用于核对响应形状。业务号、目录、通道及价格示例都来自隔离测试，不能作为目标环境配置。

## 1. 接入约定

| 项目 | 约定 |
|---|---|
| 基础地址 | 使用联调环境提供的 API 根地址；若网关有统一前缀，将前缀保留。OpenAPI 的 `/` 是相对地址，未指定生产域名 |
| 编码 | UTF-8；普通写请求 `Content-Type: application/json`；上传使用 multipart，由网络库生成 boundary |
| 账号认证 | `Authorization: Bearer <BTK>`；不发送 C 端 CTK，不把门店或 Plan 塞入账号 token |
| 选店 | 新流程统一发送 `X-Store-No: <selectedStoreNo>`；可选 `X-Entity-No` 必须匹配该店主体。路径、query、body 的目标有冲突时拒绝 |
| 无店阶段 | OTP、登录为游客入口；初始 profile、stores/mine、经营类型与 Plan 目录无需旧店头。无主体申请恢复/首次协议按接口例外处理 |
| 成功判断 | 同时判断 HTTP 与 `{code,msg,data}`；`code=0` 为成功，msg 文案不可作为程序分支条件 |
| 空值 | 无申请为 `data=null`，无列表项为 `[]`。字段不存在与显式 null 均应按字段合同处理，不能把 null 转成 0/空对象再推断状态 |
| 时间 | 本流程数值时间均为 UTC epoch 毫秒；0 可能代表尚未发生（如 agreedAt/auditedAt），null 有其业务含义；OCR 日期为文本 |
| 身份 | 个人 USR 可与 C 端共享账号身份，CTK/BTK 池独立；MCH 为员工身份。创建首店、本人申请和协议确认要求 USR；员工不能冒充同号个人 |
| 岗位 | `biz:store` 管理资质；`biz:finance` 处理收款进件；注册及恢复阶段不以已购端证明为前置。拥有 FREE 不等于资质/收款已获批 |
| 路径 | 使用字面、规范的 `/biz/...` 路径；不可用百分号编码路径绕过门禁 |
| 数据安全 | 短信码、密码、结算账号不入日志；结算账号只本次提交，响应仅给掩码，不在前端持久化明文 |

模型表中“必有字段”表示当前合同的字段存在性，不代表值非 null；类型中明确写出 null。请求必填为本流程提交要求，另有主数据、岗位、治理、材料及状态条件校验。兼容旧端可能接收更少资料，新 B 端按完整表单提交，不依赖后端兼容缺口。

## 2. 标准调用流程

| 阶段 | 顺序与接口 | 完成条件及下一步 |
|---|---|---|
| 注册登录 | POST otp/send → 用户收码 → POST auth/login（PHONE_OTP） | 登录即注册，无独立 register 接口；保存 token 与 merchant。超时不假定验证码未消费 |
| 恢复账号 | GET merchant/profile + GET stores/mine | 登录已带同形档案；冷启动重查可访问门店。清旧店头后恢复，再选择列表内确切店号 |
| 无店开店 | GET business-types → 可选 GET business-types/{typeCode}/plans → POST quick-start | 从真实类型选择；用响应 currentStoreNo 精确选店。FREE 已同事务绑定，不再另调“领取 FREE” |
| 准备资料 | GET common/master-data + GET biz/communities | industry、subject、serviceScope 和覆盖社区使用当前主数据；与商业 businessTypeCode 区分 |
| 证照采集 | POST upload/image（QUAL）→ 可选 POST qualifications/recognize | 使用上传 URL；OCR 仅辅助填表，未识别可手填，不替代上传或申请提交 |
| 恢复申请 | GET merchant/apply | 账号级最近申请；核对 merchantNo。其他主体的最近申请不能直接回填本店 |
| 提交入驻资料 | POST merchant/apply | 返回 MerchantProfile，不是申请单。再读 GET apply 查看 PENDING/REVIEWING/REJECTED/APPROVED |
| 确认协议 | 用户阅读确认 → POST merchant/agreement/accept | 已有本人的匹配申请后调用。登录 agreed 不等于商家协议落库；返回 0 是无申请，不算已签凭据 |
| 已登记资质（按需） | GET qualifications → POST qualifications/save | 这是主体资质管理，不要求先逐条保存才可提交申请；保存不自动授类目码 |
| 收款资料（按需） | GET pay-channel + GET payment → 补 missing → POST payment | AGGREGATED 不以独立进件作为开店前置；DIRECT 按实际通道办理；先区分默认收款行/独立店行 |
| 分店独立收款（按需） | POST payment/store/{storeNo} → POST payment（storeNo 同选店） | 占位仅按独立结算需要创建；不等于已经提交渠道，重复开户先读回状态 |
| 提交后恢复 | GET payment → POST payment/{payChannel}/refresh | submitted=true 等回查；超时不盲目重复开户/提交；最终以实际渠道资格为准 |

`businessTypeCode` 是门店商业经营类型，`industry` 是证照审核行业，`businessMode` 是经营模式，`fundsMode` 是资金路径，四者独立。三档展示及权益查询详见 [基础链路文档](B端基础链路-对接文档.md)，本流程所有 Plan 卡片 purchaseAvailable=false。

### 2.1 状态与页面动作

| 数据来源 | 状态/字段 | 客户端动作 |
|---|---|---|
| profile | 无主体 NONE，或有主体 PENDING_LICENSE | 无店先建店；有待补证店进入资料页，不以 FREE 推断可上架/收款 |
| application | PENDING / REVIEWING | 展示真实申请进度，不重复 POST apply |
| application | REJECTED + rejectReason | 读回资料和驳回原因，按可编辑条件补交；10467 时刷新状态 |
| application | APPROVED | 刷新 profile、授权资质及实际收款状态；不直接假定支付通道 ACTIVE |
| profile | agreementPending=true | 仅本人最近代填单未签且主体匹配时补签。A 店不能确认最近属于 B 的申请，即使 A 有更早的申请 |
| payment | NONE，或 APPLYING 且 submitted=false | 按 missing 补材料，提交后重读 |
| payment | APPLYING 且 submitted=true | 已提交渠道，等待并主动回查，不重新开户 |
| payment | REJECTED | 显示 rejectReason 后补交 |
| payment | ACTIVE | 仍读 canReceiveMoney 决定可收款；不再重复提交 |
| payment | FROZEN | 联系平台；不显示为付费 Plan 到期 |

无主体时，协议接口只接受本人尚未绑定主体的初次申请。已失去主体成员权限的个人账号，不能通过省略选店确认原主体协议。GET apply 始终是账号最新口径，本轮没有逐主体申请历史接口。

### 2.2 幂等、重试与切店

- quick-start 同类型重试复用待补证店；改类型会拒绝。成功后用服务端 currentStoreNo，不凭本地默认店推测。
- 提交申请受一个申请人一份进行中申请约束；没有通用 Idempotency-Key。超时先读 GET apply/profile，不能靠“重发直到成功”。
- 协议对同一申请返回首次确认时间。资质新增没有通用幂等键，超时先读列表；编辑携原 qualNo。
- 开户、提交收款资料、回查是三个动作。主体默认行的 storeNo 为 null/空串；提交默认行时省略 storeNo。独立店行才传所选店号。请求拦截器不能自动向所有 body 填当前 storeNo。
- 切店丢弃旧店请求、端证明与商业缓存；请求捕获账号、店号和切店序号，响应只更新匹配的当前页面。保留账号 token，不能因岗位或商业拒绝把用户登出。
- 纯 LEGACY 旧客户端可省略选店头，但仍检查默认店方法级财务岗及显式目标店财务权；列表仅主体默认行和当前授权店行。新 B 端统一发送选店头即可。

## 3. 统一错误处理

| HTTP/业务码 | 含义 | B 端动作 |
|---|---|---|
| HTTP 401；10401/10402 | 未登录、过期或已撤销会话 | 清失效凭据并重新登录 |
| 10400 / 非规范路径 HTTP 400 | 参数、文件格式/大小、缺材料、状态不允许等 | 按当前表单、missing 和状态修正；不要自动重发 |
| 10450 / 10451 | 发码过频 / 超出当日限制 | 等待或提示用户稍后再试，不循环发码 |
| 10454 / 10455 | 验证码锁定 / 无效或已消费 | 重新获取有效验证码 |
| 10456 / 10457 | 密码错误 / 未设置密码 | 使用 OTP，登录后按需设置密码 |
| 10403 或 HTTP 403/70006 | 成员、岗位、主体/门店目标不匹配 | 保留账号，重新核对选择与授权。旧宿主可返回 HTTP200+10403；前置门禁可先返回 HTTP403+70006 |
| 10404 | 目标不存在或不属于该主体 | 重读当前门店/资质/进件行；不退回默认店代执行 |
| 10409 | 进行中申请、重复操作或参数状态冲突 | 先读已有结果，不将其当作成功 |
| 10467 | 申请不在可编辑条件 | 刷新申请，保留输入并显示当前状态 |
| 70001 | 行业与主体类型不允许 | 按 industries.microAllowed 与 subjects.industryGated 调整 |
| 70198 | 创建新模型店缺少/不符合经营类型配置 | 重读发布目录；不可补造测试类型 |
| 70045 | 支付通道或 provider 不可用 | 展示暂不可进件，不伪造 submitted/ACTIVE |
| HTTP503/10500、CHECK_UNAVAILABLE | 当前身份/快照或服务暂不可核验 | 稍后重试核验，不继续跨店操作 |
| HTTP409/40040、CONTEXT_REFRESH_REQUIRED | 后续业务端上下文需刷新 | 按具体接口重新核验，保留账号 |

错误信封示例：

```json
{"code":10403,"msg":"<服务端错误文案>","data":null}
```

只依据 HTTP200 不足以判断成功。网络、代理或超时没有可解析信封时，保留表单并按各写接口的查询恢复规则处理。

## 4. 逐接口合同

以下普通请求继承 Authorization 约定；public 不需 token，store 接口按表要求选店。每项的成功 data 类型对应第 5 节完整字段表。OpenAPI 同步声明 HTTP200 的成功及业务错误两种信封。

### sendRegistrationOtp · 发送注册/登录短信验证码

`POST /biz/auth/otp/send`

权限：有效商家账号；作用域：public。

没有单独注册接口；短信验证登录时自动建立个人账号。验证码一次性消费，重复发码受服务端限制。

请求体：`application/json`，必需；结构：`OtpSendRequest`。

```json
{
  "phone": "13800000000"
}
```

成功 `data`：`null`。字段定义见后文模型表。

重试：不要自动循环发码；10450/10451 等待后由用户重试。

主要业务码：`10400 / 10450 / 10451`。含义与客户端动作见统一错误表；该列表非穷举。

### loginForOnboarding · 注册或登录

`POST /biz/auth/login`

权限：有效商家账号；作用域：public。

PHONE_OTP 首次登录即注册。个人 USR 可跨 C/B 共享身份，CTK/BTK 会话池独立；没有个人商家主体且匹配员工时可选择 MCH 会话。

请求体：`application/json`，必需；结构：`LoginRequest`。

```json
{
  "grantType": "PHONE_OTP",
  "principal": "13800000000",
  "credential": "<短信验证码>",
  "agreed": true
}
```

成功 `data`：`OnboardingLoginResponse`。字段定义见后文模型表。

重试：超时不能假定验证码未消费；重新获取验证码或重新登录。

主要业务码：`10400 / 10454 / 10455 / 10456 / 10457 / 10500`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "<BTK>",
    "merchant": {
      "merchantNo": "",
      "name": "",
      "logo": "",
      "status": "NONE",
      "subject": "PERSONAL",
      "tier": "SMALL",
      "phone": "15965010103",
      "isPickupPoint": false,
      "pickupNo": null,
      "rejectReason": null,
      "industry": null,
      "description": null,
      "fundsMode": null,
      "agreementPending": false,
      "favoriteCount": 0,
      "displayName": "",
      "currentStoreNo": null
    }
  }
}
```

### staffLoginForOnboarding · 员工 OTP 登录（兼容）

`POST /biz/auth/staff-login`

权限：有效商家账号；作用域：public。

MCH 员工不能冒充同号个人提交申请或确认协议。

请求体：`application/json`，必需；结构：`StaffLoginRequest`。

```json
{
  "phone": "13800000000",
  "code": "<短信验证码>"
}
```

成功 `data`：`OnboardingLoginResponse`。字段定义见后文模型表。

重试：同手机号验证码一次性消费。

主要业务码：`10400 / 10403 / 10454 / 10455`。含义与客户端动作见统一错误表；该列表非穷举。

### getPersonalPasswordStatus · 查询是否设置个人密码

`GET /biz/auth/password`

权限：USR；作用域：account。

仅个人 USR，会话有效不代表 MCH 可使用。

请求体：无。

成功 `data`：`HasPassword`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10401 / 10402 / 10403`。含义与客户端动作见统一错误表；该列表非穷举。

### setPersonalPassword · 设置个人密码

`POST /biz/auth/password`

权限：USR；作用域：account。



请求体：`application/json`，必需；结构：`PasswordRequest`。

```json
{
  "password": "<至少6位的新密码>"
}
```

成功 `data`：`null`。字段定义见后文模型表。

重试：超时先尝试查询/登录；不要记录密码。

主要业务码：`10400 / 10401 / 10402 / 10403`。含义与客户端动作见统一错误表；该列表非穷举。

### logoutCurrentMerchantSession · 注销本次商家会话

`POST /biz/auth/logout`

权限：有效商家账号；作用域：account。

不接受目标 token；客户端清本地账号、选店和端证明。

请求体：无。

成功 `data`：`null`。字段定义见后文模型表。

重试：重复时可能返回会话失效；仍应完成本地清理。

主要业务码：`10401 / 10402`。含义与客户端动作见统一错误表；该列表非穷举。

### getOnboardingProfile · 读取商家档案

`GET /biz/merchant/profile`

权限：有效商家账号；作用域：account。

登录已返回同形 merchant；无店也可读。恢复初始档案时不带旧选店头，选店后刷新时带该店头。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 否/按场景 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：无。

成功 `data`：`OnboardingMerchantProfile`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### getSelectableStores · 恢复本人可选门店

`GET /biz/stores/mine`

权限：有效商家账号；作用域：account。

按主体分组，只包含可信身份可访问门店。冷启动/切店前读取；空数组表示无可进入门店。

请求体：无。

成功 `data`：`EntityStores[]`。字段定义见后文模型表。

重试：可重读；废弃上次会话的旧选择。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### getOnboardingBusinessTypes · 选择可建店经营类型

`GET /biz/business-types`

权限：有效商家账号；作用域：account。

无需已有门店。仅启用且三档完整发布、FREE 可初始化的经营类型。空数组须显示暂不可建店。

请求体：无。

成功 `data`：`BusinessType[]`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "businessTypeCode": "RETAIL",
      "displayName": "Retail",
      "catalogVersion": 1
    }
  ]
}
```

### getOnboardingPlans · 查看经营类型三档 Plan

`GET /biz/business-types/{typeCode}/plans`

权限：有效商家账号；作用域：account。

当前三档只读；FREE/PRO/CHAIN 固定顺序，付费档停售仍保留卡片但无价格；全部 purchaseAvailable=false。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| typeCode | path | string | 是 | 精确编码；不得百分号编码 /biz 路径。 |

请求体：无。

成功 `data`：`OnboardingPlanCatalog`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "businessTypeCode": "RETAIL",
    "catalogVersion": 1,
    "status": "PUBLISHED",
    "plans": [
      {
        "planNo": "FREE",
        "planVersionNo": "FREE-v",
        "tier": "FREE",
        "displayName": "FREE",
        "summary": "summary",
        "entitlementCodes": [
          "BASE"
        ],
        "priceOptions": [],
        "priceStatus": "INCLUDED",
        "purchaseAvailable": false,
        "saleStatus": "ON_SALE"
      },
      {
        "planNo": "PRO",
        "planVersionNo": "PRO-v",
        "tier": "PRO",
        "displayName": "PRO",
        "summary": "summary",
        "entitlementCodes": [
          "BASE"
        ],
        "priceOptions": [],
        "priceStatus": "UNCONFIGURED",
        "purchaseAvailable": false,
        "saleStatus": "ON_SALE"
      },
      {
        "planNo": "CHAIN",
        "planVersionNo": "CHAIN-v",
        "tier": "CHAIN",
        "displayName": "CHAIN",
        "summary": "summary",
        "entitlementCodes": [
          "BASE"
        ],
        "priceOptions": [],
        "priceStatus": "UNCONFIGURED",
        "purchaseAvailable": false,
        "saleStatus": "ON_SALE"
      }
    ]
  }
}
```

### quickStartOnboardingStore · 创建首店并初始化 FREE

`POST /biz/merchant/quick-start`

权限：USR；作用域：account。

门店型模式先选已发布经营类型。建店与 FREE 同事务，成功后精确使用 currentStoreNo；既有待补证店同类型重试复用，不重复赠送。

请求体：`application/json`，必需；结构：`QuickStartRequest`。

```json
{
  "storeName": "Selected pending shop",
  "businessTypeCode": "RETAIL"
}
```

成功 `data`：`OnboardingMerchantProfile`。字段定义见后文模型表。

重试：超时同类型原参数重试；改类型拒绝。已有旧店不在重试中自动迁移。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 10467 / 70001 / 70198`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "merchantNo": "ENTITY-EXAMPLE",
    "name": "Selected pending shop",
    "logo": "",
    "status": "PENDING_LICENSE",
    "subject": null,
    "tier": "SMALL",
    "phone": "15965010103",
    "isPickupPoint": false,
    "pickupNo": null,
    "rejectReason": null,
    "industry": null,
    "description": null,
    "fundsMode": "AGGREGATED",
    "agreementPending": false,
    "favoriteCount": 0,
    "displayName": "",
    "currentStoreNo": "STORE-EXAMPLE"
  }
}
```

### getOnboardingMasterData · 获取证照及收款表单字典

`GET /common/master-data`

权限：有效商家账号；作用域：public。

证照行业 industries 与商业经营类型 business-types 不同；按 microAllowed、needLicense、industryGated、serviceScopes 渲染，不把意向行业直接用于开店。

请求体：无。

成功 `data`：`MasterData`。字段定义见后文模型表。

重试：可重读；以实际返回为准。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### getOnboardingCommunities · 选择经营覆盖社区

`GET /biz/communities`

权限：有效商家账号；作用域：account。

用于 communityNos；不需要定位参数。

请求体：无。

成功 `data`：`Community[]`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### uploadOnboardingCertificate · 上传证照图片

`POST /biz/upload/image`

权限：owner 或 biz:store 或 biz:finance；作用域：store。

multipart/form-data；由网络库自动生成 boundary，不手动设 JSON Content-Type。证照按主体管理，上传不等于保存资质或授予权限。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：`multipart/form-data`，必需；结构：`object`。

成功 `data`：`UploadResult`。字段定义见后文模型表。

重试：超时可能已生成文件；新上传可能产生另一资源，避免自动无限重传。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### recognizeOnboardingCertificate · 识别证照（可选）

`POST /biz/qualifications/recognize`

权限：biz:store；作用域：store。

真实可解码图片最大 12 MiB。recognized=false 是正常结果，允许手填；本接口不提供持久化 imageUrl，仍须调用上传接口。日期 issuedAt/validTo 为识别文本，不能直接当毫秒。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：`multipart/form-data`，必需；结构：`object`。

成功 `data`：`CertRecognition`。字段定义见后文模型表。

重试：可按用户操作重试；不自动循环识别。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### getOnboardingQualifications · 读取已登记资质和授权码

`GET /biz/qualifications`

权限：biz:store；作用域：store。

items 是登记事实；grantedCodes 才是已授类目权限。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| entityNo | query | string | 否/按场景 | 可选；必须等于所选店主体。 |

请求体：无。

成功 `data`：`OnboardingQualifications`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### saveOnboardingQualification · 新增或编辑资质

`POST /biz/qualifications/save`

权限：biz:store；作用域：store。

与申请中的 qualificationItems 是不同写入入口；不要求为提交申请先逐条保存。编辑只能传本主体 qualNo；保存不会自动授码。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：`application/json`，必需；结构：`SaveQualificationRequest`。

成功 `data`：`Qualification`。字段定义见后文模型表。

重试：新增无通用幂等键，超时先读列表；编辑带原 qualNo。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

### getLatestOnboardingApplication · 读取本人最近入驻申请

`GET /biz/merchant/apply`

权限：USR 本人；作用域：store。

无申请返回 data=null。此路径是账号最近申请；切店不会切换申请历史。回填前必须比对 merchantNo 与所选主体。真正未建店的初次恢复可不带选店头。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 否/按场景 | 所选确切门店号；必须与路径/query/body一致。 新模型已建店的操作须传；真正无店恢复/初次协议除外。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：无。

成功 `data`：`OnboardingApplication | null`。字段定义见后文模型表。

重试：提交超时或驳回后先重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 10467 / 70001 / 70198`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "applyNo": "APPLY-EXAMPLE",
    "merchantNo": "ENTITY-EXAMPLE",
    "name": "License shop",
    "subject": "ENTERPRISE",
    "contactName": "Owner",
    "contactPhone": "15965010103",
    "category": null,
    "desc": null,
    "serviceScope": "COMMUNITY",
    "communityNos": [
      "CM001"
    ],
    "licenses": [
      "example-license"
    ],
    "asPickupPoint": false,
    "industry": "RETAIL",
    "industryNote": null,
    "status": "PENDING",
    "rejectReason": null,
    "createdAt": 1793717763000,
    "auditedAt": 0,
    "qualificationItems": [],
    "onBehalf": false,
    "agreedAt": 1793717763513,
    "referrerPhone": null
  }
}
```

### submitOnboardingApplication · 提交入驻证照资料

`POST /biz/merchant/apply`

权限：USR 有效属主；作用域：store。

门店型模式须先建店，绑定所选待补证主体。一个申请人同时只能有一份进行中申请。新 B 端应采集完整结构化资质，不利用旧端省略字段的兼容路径绕过材料校验。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：`application/json`，必需；结构：`ApplyRequest`。

```json
{
  "name": "License shop",
  "industry": "RETAIL",
  "serviceScope": "COMMUNITY",
  "communityNos": [
    "CM001"
  ],
  "licenses": [
    "example-license"
  ],
  "contactPhone": "15965010103",
  "contactName": "Owner",
  "subject": "<subjects.subjectType>"
}
```

成功 `data`：`OnboardingMerchantProfile`。字段定义见后文模型表。

重试：超时先 GET apply 和 profile，不盲目重提；有进行中申请可返回10409，申请不可编辑可返回10467。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 10467 / 70001 / 70198`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "merchantNo": "ENTITY-EXAMPLE",
    "name": "Selected pending shop",
    "logo": "",
    "status": "PENDING_LICENSE",
    "subject": null,
    "tier": "SMALL",
    "phone": "15965010103",
    "isPickupPoint": false,
    "pickupNo": null,
    "rejectReason": null,
    "industry": null,
    "description": null,
    "fundsMode": "AGGREGATED",
    "agreementPending": false,
    "favoriteCount": 0,
    "displayName": "",
    "currentStoreNo": "STORE-EXAMPLE"
  }
}
```

### acceptOnboardingMerchantAgreement · 确认商家服务协议

`POST /biz/merchant/agreement/accept`

权限：USR 本人；作用域：store。

用户明确确认后调用。必须已有本人最近申请且其主体匹配所选主体；不能因登录 agreed=true 就认定已确认。无主体时只可确认本人 entity_no=NULL 初始申请；MCH 不可代签。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 否/按场景 | 所选确切门店号；必须与路径/query/body一致。 新模型已建店的操作须传；真正无店恢复/初次协议除外。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |

请求体：`application/json`，可省略；结构：`AgreementRequest`。

成功 `data`：`integer`。字段定义见后文模型表。

重试：同申请重试返回原时刻；0表示无申请，不代表成功签署凭据。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": 1793717763513
}
```

### getOnboardingPaymentChannels · 读取可用收款进件通道

`GET /biz/merchant/pay-channel`

权限：biz:finance；作用域：store。

含尚未开通通道的 NONE 占位；渠道取实际返回。AGGREGATED 不以独立进件作为建店前置。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| entityNo | query | string | 否/按场景 | 可选；必须等于所选主体。 |

请求体：无。

成功 `data`：`PaymentApplyment[]`。字段定义见后文模型表。

重试：可重读。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 70045`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": [
    {
      "payChannel": "WECHAT",
      "channelName": "微信支付",
      "applyStatus": "NONE",
      "canReceiveMoney": false,
      "payMerchantNo": null,
      "subMchidMasked": null,
      "settleAccountType": null,
      "settleAccountMasked": null,
      "rejectReason": null,
      "missing": [
        "settleAccountType",
        "settleAccount",
        "licenses"
      ],
      "submitted": false,
      "appliedAt": null,
      "activatedAt": null,
      "storeNo": ""
    },
    {
      "payChannel": "ALIPAY",
      "channelName": "支付宝",
      "applyStatus": "NONE",
      "canReceiveMoney": false,
      "payMerchantNo": null,
      "subMchidMasked": null,
      "settleAccountType": null,
      "settleAccountMasked": null,
      "rejectReason": null,
      "missing": [
        "settleAccountType",
        "settleAccount",
        "licenses"
      ],
      "submitted": false,
      "appliedAt": null,
      "activatedAt": null,
      "storeNo": ""
    },
    {
      "payChannel": "TEST",
      "channelName": "测试渠道",
      "applyStatus": "NONE",
      "canReceiveMoney": false,
      "payMerchantNo": null,
      "subMchidMasked": null,
      "settleAccountType": null,
      "settleAccountMasked": null,
      "rejectReason": null,
      "missing": [
        "settleAccountType",
        "settleAccount",
        "licenses"
      ],
      "submitted": false,
      "appliedAt": null,
      "activatedAt": null,
      "storeNo": ""
    }
  ]
}
```

### getOnboardingPaymentApplications · 读取进件状态及缺失项

`GET /biz/merchant/payment`

权限：biz:finance；作用域：store。

仅主体默认行和当前授权店行；必须按 storeNo 区分默认与独立结算。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| entityNo | query | string | 否/按场景 | 可选；必须等于所选主体。 |

请求体：无。

成功 `data`：`PaymentApplyment[]`。字段定义见后文模型表。

重试：提交超时先读此接口，再主动回查。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 70045`。含义与客户端动作见统一错误表；该列表非穷举。

### openOnboardingStorePayment · 创建分店独立收款进件占位（按需）

`POST /biz/merchant/payment/store/{storeNo}`

权限：biz:finance；作用域：store。

仅需要分店独立结算时调用；路径与选店一致。不调用则可使用主体默认收款行。占位不等于提交渠道。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| storeNo | path | string | 是 | 精确编码；不得百分号编码 /biz 路径。 |

请求体：`application/json`，可省略；结构：`OpenPaymentRequest`。

成功 `data`：`PaymentApplyment`。字段定义见后文模型表。

重试：重复开户会拒绝；超时先读状态，不自动重复开户。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 70045`。含义与客户端动作见统一错误表；该列表非穷举。

### submitOnboardingPaymentMaterials · 提交收款进件资料

`POST /biz/merchant/payment`

权限：biz:finance；作用域：store。

主体默认进件省略 storeNo；独立结算传所选店号，须存在对应行/按流程先创建。按 missing 补材料。ACTIVE 不接受重复提交；真实结果由通道决定。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| storeNo | query | string | 否/按场景 | 兼容参数；推荐只在 body 传，若与 body/头冲突会拒绝。 |

请求体：`application/json`，必需；结构：`OnboardingPaymentRequest`。

```json
{
  "payChannel": "<实际可用通道>",
  "settleAccountType": "PERSONAL_BANK_CARD",
  "settleAccount": "<真实结算账户>",
  "contactName": "Test owner",
  "contactPhone": "15965010103",
  "licenses": [
    "example-license"
  ]
}
```

成功 `data`：`PaymentApplyment`。字段定义见后文模型表。

重试：非幂等：超时先查已有行/refresh，确认未提交再让用户补交；不生成客户端假成功。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 70045`。含义与客户端动作见统一错误表；该列表非穷举。

响应示例（隔离测试数据，仅用于形状对照）：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "payChannel": "WECHAT",
    "channelName": "微信支付",
    "applyStatus": "ACTIVE",
    "canReceiveMoney": true,
    "payMerchantNo": "PAY-EXAMPLE",
    "subMchidMasked": "****2269",
    "settleAccountType": "PERSONAL_BANK_CARD",
    "settleAccountMasked": "****9999",
    "rejectReason": null,
    "missing": [],
    "submitted": true,
    "appliedAt": 1793717763907,
    "activatedAt": 1793717763911,
    "storeNo": null
  }
}
```

### refreshOnboardingPaymentStatus · 主动回查收款进件结果

`POST /biz/merchant/payment/{payChannel}/refresh`

权限：biz:finance；作用域：store。

主体默认行不传 storeNo；独立行传所选店号。回查不等于重新提交开户资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| X-Store-No | header | string | 是 | 所选确切门店号；必须与路径/query/body一致。 |
| X-Entity-No | header | string | 否/按场景 | 可选所选主体号；出现时必须属于所选店。 |
| payChannel | path | string | 是 | 精确编码；不得百分号编码 /biz 路径。 |
| storeNo | query | string | 否/按场景 | 可选独立结算店号；出现时必须匹配所选店及 body。 |

请求体：`application/json`，可省略；结构：`PaymentRefreshRequest`。

成功 `data`：`PaymentApplyment`。字段定义见后文模型表。

重试：可由用户刷新；不要密集轮询，轮询间隔不是固定服务端合同。

主要业务码：`10400 / 10401 / 10402 / 10403 / 10404 / 10409 / 10500 / 70006 / 70045`。含义与客户端动作见统一错误表；该列表非穷举。

## 5. 模型与字段字典

### AgreementRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 可选目标店；出现时必须等于 X-Store-No。 |

### ApplyRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| name | string | 是 | 拟用/执照正式名称；B 端应必填。 |
| subject | string | null | 否 | 经营主体类型，取 subjects.subjectType；是否需执照及小微限制取主数据。 |
| contactName | string | null | 否 | 联系人；B 端应采集。 |
| contactPhone | string | 是 | 联系电话；B 端应必填。 |
| category | string | null | 否 | 主营类目；申请可选。 |
| desc | string | null | 否 | 简介。 |
| serviceScope | string | null | 否 | 取主数据 serviceScopes；省略时服务端使用 COMMUNITY。 |
| communityNos | string[] | 否 | COMMUNITY 审核通过时须非空；从 communities 选择。 |
| licenses | string[] | 否 | 兼容纯图片列表；不替代结构化资质。 |
| asPickupPoint | boolean | 否 | 是否申请承接自提点；省略等价 false。 |
| industry | string | null | 否 | 证照审核行业，取 industries.industry；不是 businessTypeCode。 |
| qualificationItems | OnboardingQualificationItem[] | 否 | 新 B 端提交结构化列表；需营业执照的主体必须包含 BUSINESS_LICENSE 且证号/图片齐全。 |
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 可选目标店；出现时必须等于 X-Store-No。 |

### BusinessType



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| businessTypeCode | string | 是 | 经营类型编码。 |
| displayName | string | 是 | 显示名。 |
| catalogVersion | integer | 是 | 当前整包发布版本。 |

### CertRecognition

证照识别结果，用来**预填**表单。与后端 `BizCertController.CertVO` 同形。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| recognized | boolean | 是 | false = 模型没开或没认出来。**端上据此让他手填，不要弹错误** —— 识别是锦上添花，没识别出来只是少省一次手打。 |
| docType | string | null | 是 | `BUSINESS_LICENSE` 营业执照 / `ID_CARD` 身份证 / `UNKNOWN` 认不出是什么证 |
| side | string | null | 是 | 身份证专用：`FRONT` 人像面 / `BACK` 国徽面。其余证件为 null |
| name | string | null | 是 | 执照上的名称，或身份证上的姓名。**不是「证件名称」那一栏** |
| code | string | null | 是 | 统一社会信用代码（执照）或身份证号。 **回给上传者本人是必要的**（他正要核对这一栏）， 但不要写进日志、不要转给第三方。 |
| legalForm | string | null | 是 | 执照类型：个体工商户 / 有限责任公司 …… |
| person | string | null | 是 | 法定代表人 / 经营者 |
| address | string | null | 是 | 住所、经营场所或住址 |
| issuedAt | string | null | 是 | 成立日期。`YYYY-MM-DD` |
| validTo | string | null | 是 | 有效期止。`YYYY-MM-DD`，或字面量 `长期` |
| confidence | number | 是 | 识别置信度；未识别为 0。 |

### Community



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| communityNo | string | null | 是 | 社区单号 |
| name | string | null | 是 | 社区名（小区名） |
| address | string | null | 是 | 社区地址 |
| cityCode | string | null | 是 | 所属城市。全市范围的商家靠它判定可达 |
| regionCode | string | null | 否 | 所属街道/镇（9 位区划码）。商家框范围时「按街道看聚落」靠它 —— 不下发的话端上只能拿到一锅平铺清单，街道视图无从分组。 |
| kind | string | null | 否 | `ESTATE` 小区 / `VILLAGE` 村 / `BUILDING` 楼栋（写字楼）。 |
| parentNo | string | null | 否 | 所属聚落（楼栋 → 小区/园区）。**为空 = 顶层聚落**，直接挂 `regionCode`。 |
| distance | number | 是 | 米 |
| pickups | Pickup[] | 是 | 本社区可用的自提点 |
| originCode | string | null | 否 | 官方村码，只有 `kind=VILLAGE` 且经官方名录开通的才有。**`regionCode` 是它挂的 街道/镇，不是它自己** —— 经营范围选择器再往下钻一层要用这个码，不能用 regionCode， 否则「牛杜村」会被当成「牛杜镇」去下钻。 |
| originName | string | null | 否 | `originCode` 对应的原始官方名（「景滑村委会」，未清理）——仅供展示/追溯， 判「是不是村委会」不要解析它，用下面的 `rural` 字段（服务端存的，不是端上猜的）。 |
| rural | boolean | 否 | 是不是村委会（`sys_region.rural`，经 origin_code 反查）。只对 kind=VILLAGE 有意义： 村委会到此为止、不再下钻；居委会/社区还能再挑具体小区。 |
| latE6 | integer | null | 否 | 官方村名录批量补录过的坐标，可能为空 |
| lngE6 | integer | null | 否 | 经度 ×1e6。**全站坐标一律 gcj02** |

### Entity

一张**证照**（营业执照）。库里叫 `mch_entity`，**对外一律叫「证照」**—— 老板不认识「主体」「实体」这两个词。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| entityNo | string | null | 是 | 商家主体号 |
| name | string | null | 是 | 执照上的名称。**待补证照时它是老板随手填的店名**—— 补齐执照、审核通过后被执照上的正式名称覆盖 |
| status | string | null | 是 | ACTIVE 营业中 / PENDING_LICENSE 待补证照 / SUSPENDED、BANNED 已停业。 **端上照它给下一步**：待补证照给「去补执照」，已停业给客服入口 |
| verified | boolean | 是 | 平台已认证。待补证照恒为 false —— 这个标是审核给的，不能自己开店就带上 |
| legalForm | string | null | 是 | 个体户 / 有限公司…… 待补证照时为空，那时还不知道是哪种 |
| storeCount | number | 是 | 这张证照下**我能进**的门店数。老板 = 全部；店员 = 只数被授权到的那几家 |
| isPrimary | boolean | 是 | 默认证照。不带 `X-Store-No` 时后端解析到的就是它 |
| canManage | boolean | 是 | 我是不是这张证照的持有人。**只有持有人能改资料、交执照、挂收款号** |

### EntityStores

一张证照 + 它下面我能进的门店。门店选择器按这个分组渲染。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| entity | Entity | 是 | 所属主体（一张证照） |
| stores | Store[] | 是 | 这张证照下我能进的门店 |

### HasPassword



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| hasPassword | boolean | 是 | 是否已设置个人登录密码。 |

### LoginRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| grantType | string | 是 | 本流程使用 PHONE_OTP；已设个人密码时可用 PASSWORD。其他已有授权方式不作为本轮验收入口。 |
| principal | string | 是 | PHONE_OTP 为手机号；PASSWORD 为绑定手机号。 |
| credential | string | 是 | 一次性短信验证码或密码；不得记录日志。 |
| agreed | boolean | 是 | 客户端应在用户勾选登录协议与隐私政策后传 true；不等于商家服务协议的落库确认。 |

### MasterData

平台主数据快照（`GET /common/master-data`）。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| industries | MasterDataIndustry[] | 是 | 可选行业。**决定能不能以小微主体进件**，也是 points_forced 默认值的来源 |
| intentIndustries | MasterDataIntentIndustry[] | 是 | 行业的**意向口径**：`industries` 之外还带上这一期尚未开放的那几档。 |
| subjects | MasterDataSubject[] | 是 | 可选主体类型（法律形态）。决定资质要求与结算账户形态 |
| channels | MasterDataChannel[] | 是 | 可用支付通道与其能力位 |
| serviceScopes | ServiceScope[] | 是 | **这一期开放的经营范围档位**（`SERVICE_SCOPE` 的启用子集，运营在后台配）。 |

### MasterDataChannel



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| payChannel | string | 是 | 通道码（`sys_pay_channel.pay_channel`），如 WECHAT |
| name | string | 是 | 展示名 |
| enabled | boolean | 是 | 通道是否可用。关掉时下单页不给这个支付方式，而不是点了才失败 |
| payMethods | string[] | 是 | 该通道支持的支付方式，如 JSAPI / APP / H5 |

### MasterDataIndustry



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| industry | string | 是 | 行业码（`sys_industry.industry`），提交申请时回传的就是它 |
| name | string | 是 | 展示名。**取服务端的**，不要在端上再维护一份翻译 |
| microAllowed | boolean | 是 | 该行业能否以小微主体进件。**false 时小微选项要禁用**，不是提交后才报错 |

### MasterDataIntentIndustry



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| industry | string | 是 | 行业码（`sys_industry.industry`） |
| name | string | 是 | 展示名。取服务端的 |
| open | boolean | 是 | 平台这一期是否已开放。 |

### MasterDataSubject



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| subjectType | MerchantSubject | 是 | 主体类型码 |
| name | string | 是 | 展示名 |
| needLicense | boolean | 是 | 要不要传营业执照 |
| industryGated | boolean | 是 | 是否受行业白名单管控（小微受管，其余不受） |
| settleAccountType | SettleAccountType | 是 | 该主体默认的结算账户形态：小微打个人，其余打对公 |

### Money



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| currency | string | 是 | 币种。 |
| minor | integer | 是 | 该币种最小单位金额。 |

### OnboardingApplication



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| applyNo | string | null | 是 | 申请业务号。 |
| merchantNo | string | null | 是 | 申请关联主体；回填前须与当前所选主体核对；可空。 |
| name | string | null | 是 | 本次提交的店铺/主体名称。 |
| subject | string | null | 是 | 申请主体类型，可能尚未确定。 |
| contactName | string | null | 是 | 申请联系人。 |
| contactPhone | string | null | 是 | 联系手机号。 |
| category | string | null | 是 | 申请主营类目。 |
| desc | string | null | 是 | 申请简介。 |
| serviceScope | string | null | 是 | 申请经营范围码。 |
| industry | string | null | 是 | 申请证照行业。 |
| industryNote | string | null | 是 | 其他入口/历史手填行业说明；本 B 端请求不接收。 |
| status | string | 是 | PENDING/REVIEWING/REJECTED/APPROVED；与 profile.status 区分。 |
| rejectReason | string | null | 是 | 审核驳回原因。 |
| referrerPhone | string | null | 是 | 历史推荐手机号，只可能回显，本 B 端申请不接收。 |
| communityNos | string[] | 是 | 申请覆盖社区号列表。 |
| licenses | string[] | 是 | 申请证照图片 URL 列表。 |
| asPickupPoint | boolean | 是 | 是否申请承接自提点。 |
| createdAt | integer | 是 | 创建毫秒时间。 |
| auditedAt | integer | 是 | 审核时间；未审核为 0。 |
| qualificationItems | OnboardingQualificationItem[] | 是 | 申请中的结构化资质，尚不等于已登记并获授权资质。 |
| onBehalf | boolean | 是 | 运营代填标记。 |
| agreedAt | integer | 是 | 协议确认毫秒；未确认为 0。 |

### OnboardingAuthCode



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| code | string | 是 | 类目准入授权码，与业务端权益 code 分开。 |
| name | string | 是 | 授权显示名。 |
| requiredQualification | string | null | 是 | 该授权要求的证件名称。 |
| qualType | string | null | 是 | 对应资质类型。 |
| categoryNames | string[] | 是 | 使用该授权的类目显示名列表。 |

### OnboardingError



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| code | integer | 是 | 非零业务码；不能只判断 HTTP。 |
| msg | string | 是 | 服务端错误信息，不作为程序分支依据。 |
| data | 任意 | 是 | 通常 null，部分准入错误可能有动作信息。 |

### OnboardingLoginResponse



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| token | string | 是 | MERCHANT 池 BTK；Authorization: Bearer 。 |
| merchant | OnboardingMerchantProfile | 是 | 以服务端返回为准；见业务规则。 |

### OnboardingMerchantProfile



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| merchantNo | string | 是 | 当前主体号；尚无主体时为空字符串。 |
| name | string | null | 是 | 当前主体/店铺名称。 |
| logo | string | null | 是 | 头像或店铺图 URL；可空。 |
| status | string | 是 | 有主体：PENDING_LICENSE/ACTIVE/SUSPENDED；无主体：NONE/APPLYING/REVIEWING/REJECTED。不是申请单状态。 |
| subject | string | null | 是 | 主体类型；未确定时可空；以主数据为准。 |
| tier | string | null | 是 | 旧主体分层/额度档位，不能当商业 Plan。 |
| phone | string | null | 是 | 登录账号手机号；可空。 |
| pickupNo | string | null | 是 | 承接的自提点号；未承接时可空。 |
| rejectReason | string | null | 是 | 档案分支的驳回原因；完整申请原因另读 GET apply。 |
| industry | string | null | 是 | 证照审核行业，不是 businessTypeCode。 |
| description | string | null | 是 | 店铺简介。 |
| fundsMode | string | null | 是 | AGGREGATED/DIRECT；尚未确定时可空。 |
| displayName | string | null | 是 | 当前登录账号显示名；未设置可空。 |
| currentStoreNo | string | null | 是 | 可信解析的当前店号；quick-start 成功后用它选新店；可空。 |
| isPickupPoint | boolean | 是 | 是否承接自提点。 |
| agreementPending | boolean | 是 | 仅本人最近代填申请未签且主体与当前档案精确匹配时提示。 |
| favoriteCount | integer | 是 | 收藏数。 |

### OnboardingPaymentRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| payChannel | string | 是 | 从 pay-channel 返回选择；不要内置测试渠道。 |
| settleAccountType | string | null | 否 | PERSONAL_BANK_CARD/MERCHANT_ID；不传按主体主数据默认；仍须满足 missing。 |
| settleAccount | string | 是 | 本次提交的真实结算账户；不得日志记录或本地持久化，不回显。 |
| licenses | string[] | 否 | 非小微提交必需；按 missing 和主体字典补齐。 |
| contactName | string | null | 否 | 渠道联系人。 |
| contactPhone | string | null | 否 | 渠道联系电话。 |
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 省略/空代表主体默认收款行；传所选店号代表独立分店行。不得自动把所有请求补成当前店号。 |

### OnboardingPlanCard



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| planNo | string | 是 | 稳定 Plan 号。 |
| planVersionNo | string | 是 | 不可变版本。 |
| tier | string | 是 | 三档顺序固定。 取值：FREE, PRO, CHAIN |
| displayName | string | 是 | 显示名。 |
| summary | string | null | 是 | 简介。 |
| entitlementCodes | string[] | 是 | 该当前发布 Plan 包含的业务端权益编码。 |
| saleStatus | string | 是 | ON_SALE/OFF_SALE；停售卡片保留。 |
| priceOptions | OnboardingPriceOption[] | 是 | 只读已发布价格；停售时为空。 |
| priceStatus | string | 是 | INCLUDED/UNCONFIGURED/DISPLAY_ONLY/OFF_SALE。 |
| purchaseAvailable | boolean | 是 | 本轮恒 false。 固定值：False |

### OnboardingPlanCatalog



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| businessTypeCode | string | 是 | 经营类型编码。 |
| catalogVersion | integer | null | 是 | 不可用时为 null。 |
| status | string | 是 | PUBLISHED/UNAVAILABLE。 |
| plans | OnboardingPlanCard[] | 是 | 不可用时为空数组，不能补造三档。 |

### OnboardingPriceOption



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| skuNo | string | 是 | SKU号。 |
| skuVersionNo | string | 是 | 发布版本。 |
| title | string | 是 | 显示名。 |
| price | Money | 是 | 以服务端返回为准；见业务规则。 |
| listPrice | Money | 是 | 以服务端返回为准；见业务规则。 |
| periodDays | integer | 是 | 周期天数。 |
| unitCount | integer | 是 | 周期份数。 |
| gift | boolean | 是 | 是否赠送。 |

### OnboardingQualificationItem



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| type | string | 是 | 证件类型；营业执照为 BUSINESS_LICENSE，其他取平台资质字典。 |
| code | string | null | 否 | 证件编号。 |
| imageUrl | string | null | 否 | 上传得到的证照 URL。 |
| expireAt | integer | null | 否 | UTC epoch 毫秒；null 为长期有效。 |
| issuer | string | null | 否 | 发证机关。 |

### OnboardingQualifications



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| items | Qualification[] | 是 | 已登记资质。 |
| grantedCodes | string[] | 是 | 实际已授予的类目授权码列表。 |
| catalog | OnboardingAuthCode[] | 是 | 授权与所需证件/类目对应字典。 |

### OpenPaymentRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| payChannel | string | null | 否 | 可选；省略由服务端选择默认可用通道。 |
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 可选目标店；出现时必须等于 X-Store-No。 |

### OtpSendRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| phone | string | 是 | 11 位手机号，字符串，短信发码与消费沿用服务端限制。 |

### PasswordRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| password | string | 是 | 已登录 USR 设置新密码；不收旧密码。 最少6字符。 |

### PaymentApplyment

收款进件状态（每通道一条）。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| payChannel | string | null | 是 | 通道码，如 WECHAT |
| channelName | string | null | 是 | 通道展示名。取服务端的，端上不要再维护一份翻译 |
| applyStatus | PaymentApplyStatus | 是 | NONE / APPLYING / ACTIVE / REJECTED / FROZEN |
| canReceiveMoney | boolean | 是 | 这个通道现在能不能收钱。 |
| payMerchantNo | string | null | 是 | 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 |
| subMchidMasked | string | null | 是 | 二级商户号掩码。完整号不回显 |
| settleAccountType | string | null | 是 | PERSONAL_BANK_CARD/MERCHANT_ID；尚未确定可为null。 |
| settleAccountMasked | string | null | 是 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| rejectReason | string | null | 是 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| missing | string[] | 是 | 还缺的字段名：settleAccountType、settleAccount、licenses 等。 |
| submitted | boolean | 是 | **有没有真的发给通道过。** |
| appliedAt | integer | null | 是 | 提交毫秒时间；可空。 |
| activatedAt | integer | null | 是 | 激活毫秒时间；可空。 |
| storeNo | string | null | 是 | null/空串为主体默认行；其他为门店独立行。 |

### PaymentRefreshRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 可选目标店；出现时必须等于 X-Store-No。 |

### Pickup



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| pickupNo | string | 是 | 自提点单号 |
| name | string | 是 | 自提点名称（通常是承接店铺的店名） |
| address | string | 是 | 自提点地址 |
| distance | number | 是 | 距当前社区的距离（米），服务端算好下发 |
| hostMerchantNo | string | 是 | 承接这个自提点的商家（ADR-005：PickupPoint.type=STORE，承接方是入驻商家而非团长） |
| hostName | string | 是 | 承接商家的店名 |
| hostAvatar | string | 是 | 承接商家的头像/门头图 |
| openHours | string | 是 | 营业时间文案，如 `08:00-21:00`。展示用，不参与计算 |
| arrivalDesc | string | 是 | 到货时间说明，如「次日 18:00 后到」。影响用户选不选这个点 |
| latE6 | number | null | 否 | 取货点坐标（gcj02，E6）。**可能为空** —— 存量点是手填地址建的。 买家要拿着它导航过去，没有就只能显示地址文本。 |
| lngE6 | number | null | 否 | 经度 ×1e6。**全站坐标一律 gcj02** |

### Qualification

已登记的一张资质（`mch_qualification`）。

| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| qualNo | string | null | 是 | 资质记录号 |
| entityNo | string | null | 否 | 归属主体。端上其实用不到（只会看自己的），但后端在发 —— 声明出来免得契约守卫把它算成缺口 |
| qualType | string | 是 | 当前资质类型编码；以平台字典为准。 |
| qualName | string | null | 是 | 证件名，如「食品经营许可证」。上架校验拿它与类目门槛的文案比对 |
| qualNumber | string | null | 否 | 证件编号，证上印的那一串 |
| imageUrl | string | null | 否 | 证件照地址 |
| expireAt | integer | null | 否 | 有效期截止（毫秒）。**空 = 长期有效**，与「已过期」是两回事 |
| status | string | null | 是 | VALID / EXPIRED / REVOKED |

### QuickStartRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| storeName | string | 是 | 必填非空店名；同时作为初始主体名。 |
| address | string | null | 否 | 门店地址，可暂不传。 |
| businessTypeCode | string | 是 | 门店型创建模式必填；取 business-types 返回的精确编码。 |

### SaveQualificationRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| qualNo | string | null | 否 | 新增省略；编辑必须是当前主体已有资质号。 |
| qualType | string | 是 | 资质类型。 |
| qualName | string | 是 | 证件名称。 |
| qualNumber | string | null | 否 | 证件编号。 |
| imageUrl | string | null | 否 | 证照 URL。 |
| expireAt | integer | null | 否 | 毫秒；null 长期有效。 |
| entityNo | string | null | 否 | 可选；必须等于所选店主体。省略由可信选店解析。 |
| storeNo | string | null | 否 | 可选目标店；出现时必须等于 X-Store-No。 |

### StaffLoginRequest



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| phone | string | 是 | 员工绑定手机号。 |
| code | string | 是 | 短信验证码。 |

### Store



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| storeNo | string | null | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| name | string | null | 是 | 门店名 |
| address | string | null | 是 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| isDefault | boolean | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| status | string | null | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| payMerchantNo | string | null | 是 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| payReady | boolean | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| staffCount | number | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| rating | number | 是 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| ratingCount | number | 是 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| planSuspended | boolean | 是 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 |
| businessMode | string | null | 是 | 经营模式（门店级）。自营门店加经营类目不判资质，「调整经营类目」面板因此不标「需资质」 （TDD-门店经营类目）。老后端不发 —— 端上按「不是自营」处理，宁可多提示不少提示。 |
| selfOperated | boolean | 是 | 所属主体是不是平台自营（mch_entity.self_operated）。后端免资质按它判， 「调整经营类目」面板标不标「需资质」也按它 —— **不要改回读 businessMode**： 那一列每家新店默认都是 SELF_OPERATED，包括第三方商家的店（TDD-门店经营类目 §10） |
| slug | string | null | 是 | 门店代码（V357）：店主自己定的那一串，对外链接 `hxmall.top/s/` 里露出来的那一段。 |
| shareUrl | string | null | 是 | 这家店的对外链接（完整地址，V357）。**后端拼好发下来，端上不自己拼** —— 域名写在端上就会有两处真源，而它已经错过一次：此前后端写死 `shop.example.com`， 商家把那个链接印了贴纸才发现指向一个不存在的地方。 |

### UploadResult



| 字段 | 类型 | 必有字段 | 说明 |
|---|---|---|---|
| url | string | 是 | 后续 licenses/imageUrl 使用该 URL。 |
| assetKey | string | 是 | 媒体资源键。 |

## 6. 联调前置与验收清单

后端需使用 api profile；新模型配置 commerce.enabled=true、commerce.store-creation.enabled=true，使用已迁移的同一主库、DB 商家会话及服务端签名密钥。新模型默认关闭，先由平台真实发布完整三档并启用类型。目标环境域名、短信与支付渠道配置由部署环境提供，不把私密配置写到客户端。

短信验证依赖真实短信服务，上传依赖媒体存储，OCR 可未配置；未配置 OCR 时 recognized=false 允许手填。收款依赖实际通道 provider，开发 STUB 的 ACTIVE 不能作为生产验收。前次基础链路验收通过独立 commerce 166 项、宿主相关 165 项与 37 项目构建；本次仅整理文档，不重新声称做过真实短信、渠道开户或生产容量验收。

B 端接入至少验证：

- 首次手机号注册、已注册 OTP 登录、验证码错误/消费后重试、个人/员工身份分支及注销。
- 无店目录为空、正确类型建店、超时重试、FREE 初始化与 currentStoreNo 精确选择、冷启动恢复。
- 字典驱动主体和执照表单，上传成功/OCR 未识别、长期证件 null、结构化材料与纯图片字段分别传递。
- 入驻提交、重复/进行中冲突、驳回补交、账号最新申请跨主体时不串回填与协议。
- 默认收款行与分店独立行、missing/submitted 区分、提交超时恢复、渠道驳回和回查。
- A 店岗位不能操作 B 店，路径/头/query/body 冲突拒绝，旧响应不覆盖新选店；商业拒绝不清 token。

前端以本文和 OpenAPI 为本流程对接入口。目录/权益扩展参考基础链路文档；总 OpenAPI 仍含其他业务和历史类型，不能直接把其中 C 端申请字段复制到 B 端。比如 referrerPhone、industryNote、settleAccountType 并不是本次 POST /biz/merchant/apply 控制器接收的字段；结算资料应传 payment 接口。


## 7. 请求执行模板

API_BASE 使用联调环境地址；BTK、STORE_NO 从真实登录/建店或门店恢复结果取得。以下只是执行模板，application.json 内容按 ApplyRequest 填写，不含真实密钥。

```sh
curl "$API_BASE/biz/auth/otp/send" \
  -H 'Content-Type: application/json' \
  --data '{"phone":"<手机号>"}'

curl "$API_BASE/biz/upload/image" \
  -H "Authorization: Bearer $BTK" \
  -H "X-Store-No: $STORE_NO" \
  -F 'bizType=QUAL' -F 'file=@/path/to/license.jpg'

curl "$API_BASE/biz/merchant/apply" \
  -H "Authorization: Bearer $BTK" \
  -H "X-Store-No: $STORE_NO" \
  -H 'Content-Type: application/json' \
  --data-binary @application.json
```

## 8. 对接补充：可信岗位、当前 FREE 与权益（2026-11-04.1）

本次跟进 Flutter 项目时补入四个已有接口，使机器合同完整覆盖本阶段29个操作。它们不是新增实现。无需调用不存在的 `/biz/auth/session`、`/biz/auth/market`，也不能伪造 actorKey、市场、币种或时区。

选店后可并行读取岗位与商业汇总：`GET /biz/context` 给出服务端实际 `perms`；`GET /biz/stores/{storeNo}/context` 给出同一快照的实际订阅与端权益。两个 context 语义不同。无主体阶段先使用 profile、stores/mine 和建店流程。

| 方法 | 路径 | 成功data | 目的 |
|---|---|---|---|
| GET | /biz/context | SelectedStorePermissions | 读取所选门店岗位权限 |
| GET | /biz/stores/{storeNo}/context | CurrentStoreCommercialContext | 读取所选门店商业与治理汇总 |
| GET | /biz/stores/{storeNo}/subscription | CurrentStoreSubscription | 读取实际 FREE 与当前订阅 |
| GET | /biz/stores/{storeNo}/entitlements | CurrentStoreEntitlements | 读取每端实际覆盖与到期状态 |

四项请求均发送 BTK 和确切 X-Store-No；X-Entity-No 可选但须匹配。门店路径参数必须与头一致。均为只读、可刷新；10401/10402 才按账号失效处理，10403/70006 保留账号，10500 暂不可核验则重试。

- 岗位入口按 `perms` 是否包含 `*` 或具体 `biz:store` / `biz:finance` 决定，最终由服务端判权。不要从 staffRoles、roles、canManage、Plan 或手机号推算权限。
- canManage 在 stores/mine 中是属主提示；canProcure 是店管理/采购岗，均不能替代收款进件财务权限。
- 实际 FREE 从 subscription.free 读取；不能把最新 FREE 卡片当本店已绑定版本。currentPlan、sources 和每端覆盖分别处理。
- 全部时间为 UTC epoch 毫秒，区间为 [start,end)。null结束/remaining表示永久，null下一边界表示没有已知边界；真实空档不能填平。
- 当前端 ACTIVE 的动作是 CHECK_END_ACCESS；该查询不会签发短证明，实际进入受保护业务端仍按原端准入合同处理。
- LEGACY 不虚构 FREE：订阅对象内 free/currentPlan/entitlementVersion为空，sources为空，nextAction=VIEW_LEGACY_PROFILE；权益items为空。

### 8.1 补充模型字段

#### SelectedStorePermissions

| 字段 | 类型 | 说明 |
|---|---|---|
| merchantNo | string | 当前主体号；未有主体时本接口拒绝。 |
| currentStoreNo | string | null | 当前所选门店号。 |
| owner | boolean | 当前主体有效属主。 |
| storeNos | string[] | 可操作门店范围。 |
| pickupNos | string[] | 可核销自提点范围。 |
| groupNos | string[] | 所发起团范围。 |
| staffRoles | string[] | 当前门店的真实角色码；不要自行映射为权限。 |
| perms | string[] | 服务端实际权限并集；属主为 ["*"]；判断 biz:store/biz:finance 等使用此集合。 |
| categoryCodes | string[] | 实际已授经营类目授权码，非商业端权益。 |
| switches | object | 允许商家读取的平台布尔开关；不包含密钥。 |

#### StoreEntitlementSource

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceNo | string | 实际来源号。 |
| sourceType | string | FREE/ORDER/GRANT。 |
| planNo | string | null | 来源固定 Plan 号；独立端来源可空。 |
| planVersionNo | string | null | 来源不可变 Plan 版本；可空。 |
| tier | string | null | 来源固定档位；独立端来源可空。 |
| displayName | string | null | 来源固定发布版本的名称，不取最新目录覆盖。 |
| gift | boolean | 是否赠送来源。 |
| planStartsAt | integer | null | Plan 生效毫秒；独立来源可空。 |
| planEndsAt | integer | null | Plan 结束毫秒；永久/独立来源可空。 |
| grantedDurationMs | integer | null | 来源贡献时长；永久时可空。 |
| entitlementCodes | string[] | 此笔实际贡献的业务端权益编码。 |

#### CurrentStoreSubscription

| 字段 | 类型 | 说明 |
|---|---|---|
| storeNo | string | 所选店号。 |
| commerceModel | string | LEGACY/STORE_V1。 |
| entitlementVersion | integer | null | 权益快照版本；LEGACY为空。 |
| free | StoreEntitlementSource | null | 首次同事务绑定的 FREE 来源；LEGACY为空，不按最新目录改写。 |
| currentPlan | StoreEntitlementSource | null | 当前固定 Plan；有限周期结束后回到已绑定FREE；LEGACY为空。 |
| sources | StoreEntitlementSource[] | 实际可读的 NORMAL 父来源；不包含金额或折算基数。 |
| serverNow | integer | 服务端 UTC epoch 毫秒。 |
| nextBoundaryAt | integer | null | 下次已知状态边界；没有时为空。 |
| purchaseAvailable | boolean | 本阶段为 false。 |
| nextAction | string | VIEW_ENTITLEMENTS；LEGACY为VIEW_LEGACY_PROFILE。 |

#### StoreCoverageInterval

| 字段 | 类型 | 说明 |
|---|---|---|
| startsAt | integer | 区间开始毫秒。 |
| endsAt | integer | null | 区间结束毫秒；null为永久。 |

#### StoreCoverageSlice

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceNo | string | 对应实际来源号。 |
| startsAt | integer | 开始毫秒。 |
| endsAt | integer | null | 结束毫秒；null为永久。 |

#### StoreBusinessEndRight

| 字段 | 类型 | 说明 |
|---|---|---|
| entitlementCode | string | 业务端权益code。 |
| endCode | string | 对应业务端编码。 |
| commercialStatus | string | ACTIVE/NOT_STARTED/EXPIRED/NOT_PURCHASED/REVOKED。 |
| current | StoreCoverageInterval | null | 当前连续覆盖；无当前覆盖为空。 |
| future | StoreCoverageInterval[] | 已合并且排序的未来覆盖区间，真实空档保留。 |
| slices | StoreCoverageSlice[] | 实际来源时间片。 |
| remainingMs | integer | null | 当前及未来覆盖并集剩余毫秒；永久为null，不能按Plan最大到期替代。 |
| nextBoundaryAt | integer | null | 此端下次状态边界；没有时为空。 |
| nextAction | string | CHECK_END_ACCESS/WAIT_FOR_START/VIEW_SUBSCRIPTION/CONTACT_OWNER/CONTACT_ADMIN等。 |

#### CurrentStoreEntitlements

| 字段 | 类型 | 说明 |
|---|---|---|
| storeNo | string | 所选店号。 |
| commerceModel | string | LEGACY/STORE_V1。 |
| entitlementVersion | integer | null | 快照版本；LEGACY为空。 |
| items | StoreBusinessEndRight[] | 实际持有证据及当前公开未购买候选；不展示仅内部登记且从未持有的端。LEGACY为空数组。 |
| serverNow | integer | 服务端 UTC epoch 毫秒。 |
| nextBoundaryAt | integer | null | 最近已知状态边界；没有时为空。 |

#### CurrentStoreCommercialContext

| 字段 | 类型 | 说明 |
|---|---|---|
| storeNo | string | 所选店号。 |
| entityNo | string | 该店主体。 |
| commerceModel | string | LEGACY/STORE_V1。 |
| businessTypeCode | string | null | 门店商业经营类型；LEGACY可空。 |
| storeStatus | string | 门店治理状态。 |
| entityStatus | string | 主体治理状态；PENDING_LICENSE不等于收款获批。 |
| owner | boolean | 当前有效成员是否属主。 |
| roles | string[] | 当前店实际角色码；权限并集另读GET /biz/context。 |
| canProcure | boolean | 是否具备本店 biz:store:admin；不等于付款已开通。 |
| serverNow | integer | 一致快照的服务端 UTC epoch 毫秒。 |
| entitlementVersion | integer | null | 权益版本；LEGACY为空。 |
| nextBoundaryAt | integer | null | 订阅与各端最近状态边界；没有时为空。 |
| subscription | CurrentStoreSubscription | 关联模型，字段均随响应返回。 |
| entitlements | CurrentStoreEntitlements | 关联模型，字段均随响应返回。 |


## 2026-11-14 T01 产品实体映射与统一消息草案

参见[经营实体映射与统一消息合同](经营实体映射与统一消息合同.md)。统一账号 SSE v2 尚未部署，不纳入当前已就绪接口；现有 v1 合同与权限不变。

## 开店编排及分区设置增量（2026-11-14，本地实现未部署）

- `POST /biz/merchant/store-openings`：`requestNo`、可选 `entityNo/sourceStoreNo`、`fields`（原 QuickStartRequest）。服务端在原建店事务内写成功回执，固定初始化已发布免费项，不自动开通支付。
- `GET /biz/merchant/store-openings/{requestNo}`：按账号／租户／身份类型读取原回执。请求未知先查原编号；未找到可同号同意图重试。更改意图返回 10409，失败回滚不留下永久处理中状态。
- `GET /biz/store/settings`：当前店资料、`sectionVersions.BASIC/HOURS`、`allowedActions`。版本为不透明内容校验值，非全店递增序号。
- `POST /biz/store/settings/basic`：`expectedVersion/name/address/addressDetail`，`biz:store:admin`；一个事务更新三个字段。
- `POST /biz/store/settings/hours`：`expectedVersion/openHours`，`biz:store`；只更新营业时间。
- 分区过期返回 10409。App 保留用户输入，提示关闭后重新读取核对，不自动覆盖。旧入口保留，缺少新版能力的老服务沿用既有接口，不能视为已具有版本冲突保护。
- store-creation-context 新增 `storeOpeningSupported=true`；缺少此能力不调用新版开店接口。迁移 V673 仅新增回执表，必须先在 API 数据库应用，当前未部署。

字段定义见本目录 `openapi-b-onboarding.json` 的 StoreOpening* 与 StoreSettings*。
