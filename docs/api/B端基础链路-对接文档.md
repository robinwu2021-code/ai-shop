# B 端基础链路对接文档

适用范围为商家账号、选型建店、证照申请、支付商户进件，以及门店的三档 Plan 展示、已绑定 FREE 和当前权益。本文接口按宿主 api profile 的实际处理器编写。文中的示例来自隔离测试，业务号做了替换。登录凭据、授权证明使用占位符。完整的请求体、响应信封和可空字段见[请求与响应 JSON](examples/b-foundation.json)。

## 从登录到恢复门店

1. 登录取得 `data.token` 和 `data.merchant`。个人与 C 端共享 USR 身份；B 端登录服务撤销凭据交换时产生的临时 CTK，按身份选择签发 USR 或 MCH 的 BTK。两端会话池独立，token 不通用。长期 BTK 只代表商家账号会话。账号入口和恢复初始档案时清除旧选店头。除登录和公开字典外，请求携带 `Authorization: Bearer <BTK>`。JSON 请求带 `Content-Type: application/json`。
2. 无店申请人可先读取经营类型和该类型的 Plan 卡片。用选中的 `businessTypeCode` 快速开店，从响应的 `data.currentStoreNo` 取新店号。建店成功时 FREE 已在同一数据库事务内绑定。遇到未配置的空目录应显示暂不可开店。
3. 冷启动先读 `GET /biz/merchant/profile` 和 `GET /biz/stores/mine`。后者返回 `[{entity,stores}]`，门店列表按可信身份、成员授权裁剪。将上次店号与列表核对，选择本次要进入的确切门店。随后按选店读取 profile、商业 context 和进件。没有商家时使用登录档案或申请恢复，`GET /biz/context` 需已有商家主体。规划中的 `/biz/auth/context` 尚无处理器。
4. 选店请求都带 `X-Store-No: <selectedStoreNo>`。带路径店号的接口必须使用相同店号。可选 `X-Entity-No`、query/body 中已有的 `entityNo`、`storeNo` 必须指向该店及其主体。三项新的门店查询在 LEGACY 和 STORE_V1 都要求店号。目录查询不需要选店，有显式选店时核对该目标。商家路径要用规范的字面路径，含百分号编码的路径会被拒绝。
5. `context` 同时返回岗位、治理状态、`subscription` 和 `entitlements`，首屏可只发一次请求。详情页可分别刷新后两项。超时重新读当前数据。若需进入受保护端，再查单端准入并取得短期证明。切店时丢弃旧店证明、旧请求响应和商业缓存。

### 账号与开店

| 方法和路径 | 请求及调用者 | 成功时的 data |
|---|---|---|
| POST /biz/auth/otp/send | 游客，`{"phone":"<手机号>"}`。限流和一次性消费沿用宿主短信通道 | null |
| POST /biz/auth/login | `{grantType,principal,credential,agreed}`。首次手机号注册使用 PHONE_OTP，principal 是手机号、credential 是收到的验证码、agreed 为 true。PASSWORD 接受已设密码的个人账号。WX_MINI／WECHAT_MP 使用小程序登录 code 作 principal；需实际微信配置。APPLE 的既有分支仍缺少签名校验，本次交付不以其作为验收入口 | `{token,merchant}`。已有个人主体优先，否则手机号 OTP 可恢复同手机号的员工 MCH 会话；其余个人使用 USR 会话。同号的两种身份由服务端区分 |
| POST /biz/auth/staff-login | 兼容员工登录，`{phone,code}` | 同上。员工手机号登录不代表拥有同号个人的申请 |
| GET、POST /biz/auth/password | 已登录 USR。POST 传 `{password}` | GET 为 `{hasPassword:boolean}`，POST 为 null |
| POST /biz/auth/logout | 当前 BTK，空体 | null。撤销该会话。清除本地 token、选店和证明 |
| GET /biz/merchant/profile | 登录时附带同形档案，可单独刷新。新账号也可读 | `merchantNo,name,logo,status,subject,tier,phone,isPickupPoint,pickupNo,rejectReason,industry,description,fundsMode,agreementPending,favoriteCount,displayName,currentStoreNo`。店号可空，无主体时 merchantNo 为空字符串 |
| POST /biz/merchant/quick-start | USR，`{storeName,address?,businessTypeCode}`。门店型建店模式下显式选已发布类型 | 上述档案，主体通常为 PENDING_LICENSE。从 currentStoreNo 选择刚建的店 |
| POST /biz/store/create | 已选店且有 biz:store:admin，服务另核实属主与主体门店额度。`{name,address,categoryNos?,entityNo?,businessTypeCode,operationNo}` | StoreVO，取 storeNo。先选择目标主体的已有门店，才可为它建分店 |
| GET /biz/store/list、GET /biz/context | 已选店的成员。前者返回当前主体内可见门店，后者含 currentStoreNo、staffRoles、perms、categoryCodes 等 | 店列表与岗位/经营上下文。使用 `stores/mine` 可跨主体选择门店 |

快速开店按申请人复用当前待补证主体及默认店。同类型重试仍返回该店，修改类型重试会被拒。分店为每次独立建店生成并持久化一个 operationNo（1–64 位字母、数字或 `_.:-`），超时原键原参数重试，包括原名称、地址和类目顺序。同键改参数会冲突。商业建店开关关闭时走旧建店合同，旧店不会在重试中被自动迁移。

`industry` 是证照申请行业，`businessTypeCode` 是门店订阅所属经营类型，`businessMode` 是 SELF_OPERATED／THIRD_PARTY 经营模式，`commerceModel` 是 LEGACY／STORE_V1。四者独立。宿主旧 `/biz/plan` 表示主体额度，受旧岗位与新模型准入边界限制。切换器和档案上的 tier、planSuspended 不能用于推断门店订阅。CHAIN 卡片也不会改变现有主体建店额度。

## 三档目录、FREE 和当前权益

| GET 路径 | 作用域 | data |
|---|---|---|
| /biz/business-types | 已登录商家账号，可无店 | `[{businessTypeCode,displayName,catalogVersion}]`，仅已启用、FREE 可初始化且三档均有当前发布指针的类型；空数组是正常结果 |
| /biz/business-types/{typeCode}/plans | 同上，使用目录中的精确编码 | `{businessTypeCode,catalogVersion,status,plans}`。PUBLISHED 时按 FREE、PRO、CHAIN 排列。未知、未发布、未启用或不完整统一为 UNAVAILABLE、null 版本和空数组 |
| /biz/stores/{storeNo}/context | 本店有效成员。店 ACTIVE、主体 ACTIVE 或 PENDING_LICENSE | `{storeNo,entityNo,commerceModel,businessTypeCode,storeStatus,entityStatus,owner,roles,canProcure,serverNow,entitlementVersion,nextBoundaryAt,subscription,entitlements}`。roles 是本店实际角色码，canProcure 取本店 biz:store:admin |
| /biz/stores/{storeNo}/subscription | 同上，无需采购岗或端证明 | `{storeNo,commerceModel,entitlementVersion,free,currentPlan,sources,serverNow,nextBoundaryAt,purchaseAvailable,nextAction}` |
| /biz/stores/{storeNo}/entitlements | 同上 | `{storeNo,commerceModel,entitlementVersion,items,serverNow,nextBoundaryAt}` |

**目录卡片。** planNo 是稳定键，planVersionNo 指向本次发布的不可变版本。tier、displayName、summary、entitlementCodes 来自该版本。saleStatus 为 ON_SALE 或 OFF_SALE。付费档停售时保留其发布卡片并清空价格，不影响同类型 FREE 开店。priceStatus 为 INCLUDED（随建店初始化）、UNCONFIGURED（无可展示价格）、DISPLAY_ONLY（有公开价格）、OFF_SALE。所有卡片的 purchaseAvailable 都是 false。版本代数 catalogVersion 表示行业整包发布，独立 SKU 调价、停售后仍应刷新实际卡片。

**价格选项。** priceOptions 仅包括相同类型、引用该精确 Plan 版本的在售 PLAN 商品及在售 SKU 当前发布版本。`skuNo,skuVersionNo,title` 为字符串；`price,listPrice` 都为 `{currency:string,minor:integer}`，金额单位是该币种最小单位。`periodDays` 和 `unitCount` 是整天数和周期份数，`gift` 为布尔值。空数组不表示零元。组合包、硬件、历史价及运营变更原因不在卡片里。

**门店订阅。** free 为首次初始化的永久来源，currentPlan 为服务端当前时刻生效的固定 Plan，有限 Plan 周期以外回到该店已绑定的 FREE。sources 为可读的 NORMAL 父来源，字段为 sourceNo、sourceType（FREE／ORDER／GRANT）、planNo、planVersionNo、tier、displayName、gift、planStartsAt、planEndsAt、grantedDurationMs、entitlementCodes。gift 标记赠送。来源的 entitlementCodes 是这一笔实际贡献的端，可不含已由永久 FREE 覆盖的端。独立端来源的 Plan 字段为空；不可变版本对应的展示名不随当前目录覆盖。暂停销售或停用经营类型不会收回已授来源。采购和普通成员均不返回实付、折价、钱包或订单基数。查看动作是 VIEW_ENTITLEMENTS。

**每端覆盖。** 未持有的端仅在启用类型的当前三档发布卡片列出、且端已启用并通过 STORE 验证时展示。单独登记端或仅有历史 Plan 绑定不会出现在候选列表。店内已有来源、排期或冻结记录的端独立展示，即使类型已停用或端已退役仍返回其实际商业状态。items 每项有 entitlementCode、endCode、commercialStatus、current、future、slices、remainingMs、nextBoundaryAt、nextAction。current 为当前连续区间或 null；future 是有序、已合并的未来区间，两者的字段均为 startsAt、endsAt。slices 通过 sourceNo 指向父来源并列出实际起止。同端重叠或首尾相接才连续，空档保留。remainingMs 是当前与未来覆盖的并集剩余毫秒；无限覆盖返回 null。按来源、按不同端分别处理，不能以某个 Plan 的最长到期日代表全部权益。所有新读接口的时间均为 UTC epoch 毫秒整数；半开区间 [startsAt, endsAt)，null 结束表示永久。serverNow 为服务端裁定时间，nextBoundaryAt 可空。context 外层 nextBoundaryAt 取订阅与各端最近边界的最小值。两个详情分次请求可能跨版本，同屏需要一致快照时读取 context。

| commercialStatus | 端上的动作 |
|---|---|
| ACTIVE | CHECK_END_ACCESS，先核实具体端和客户端准入 |
| NOT_STARTED | WAIT_FOR_START，目前处于开始前或两段覆盖之间 |
| EXPIRED / NOT_PURCHASED | 采购岗 VIEW_SUBSCRIPTION，普通成员 CONTACT_OWNER |
| REVOKED | CONTACT_ADMIN。撤销、冻结及有效来源分别处理 |

**旧模型。** LEGACY 的 subscription 返回 free、currentPlan、entitlementVersion、nextBoundaryAt 为 null，sources 为空，nextAction 为 VIEW_LEGACY_PROFILE；entitlements 的 items 为空。仅可进入相应旧模型流程。STORE_V1 读不到完整账户／排期会返回核验失败。两种分支均不通过账号 token 推断。

## 证照、协议与授权

| 方法和路径 | 使用方式与返回 |
|---|---|
| GET /common/master-data | 可公开读取。按 industries 的 microAllowed、subjects 的 needLicense／industryGated、serviceScopes、channels 渲染进件表单。intentIndustries 含未开放意向，开店三档另取 business-types |
| GET /biz/communities | 已登录，取可选社区字典，communityNos 引用实际社区号 |
| POST /biz/upload/image | multipart/form-data，file 必填，证照传 bizType=QUAL，最多 5 MiB。返回 `{url,assetKey}`。选店需要 owner、biz:store 或 biz:finance |
| POST /biz/qualifications/recognize | 本店 biz:store，multipart 的 file，最多 12 MiB。返回 recognized、docType、side、name、code、legalForm、person、address、issuedAt、validTo、confidence。日期识别字段为文本。未配置识别器或未识别时 recognized=false，可手填 |
| POST /biz/merchant/apply | USR 本人。门店型建店先开店，选中本人仍有效持有的待补证主体的门店。name、subject、contactName、contactPhone、industry、serviceScope、communityNos、licenses 按字典和主体校验；可带 category、desc、asPickupPoint、qualificationItems。返回 MerchantProfile |
| GET /biz/merchant/apply | USR 账号的最近申请或 null。切换门店后仍返回同一份，回填前校对 merchantNo 与所选主体。含 applyNo、status、rejectReason、agreedAt、qualificationItems 等；MCH 员工不能用此接口恢复同号个人申请 |
| POST /biz/merchant/agreement/accept | USR，通常空 body。已有主体时，确认本人最近申请须匹配当前主体；纯旧店省略选店头时取当前默认主体。无主体的个人只能确认本人尚未关联主体的初始申请。返回首次同意的毫秒时间，重试仍返回原值，无申请返回 0。 |
| GET /biz/qualifications | 本店 biz:store。返回 `{items,grantedCodes,catalog}`，catalog 含所需证照和类目 |
| POST /biz/qualifications/save | 本店 biz:store。传 `{qualNo?,qualType,qualName,qualNumber?,imageUrl?,expireAt?,entityNo?,storeNo?}`。编辑传本主体 qualNo，返回保存的资质对象。新增无 qualNo，网络超时先重读列表 |

结构化申请项为 `{type,code,imageUrl,expireAt,issuer}`，长期有效传 null，其他有效期传毫秒。上传、OCR 和保存证件后，实际类目授权仍查 grantedCodes。提交前显示协议与用户确认，适配本人的申请。没有显式选店的旧申请仅在无歧义、有效 owner 的纯旧候选时可认领；无候选且建店开关未开启时保留直接申请。跨主体或混合模型应先让用户选择确切待补证店。

登录返回的档案和刷新档案使用相同的补签规则。本人最近一份申请由运营代填且尚未同意时，无主体的个人仅在该申请尚未关联主体时看到 agreementPending=true。已有主体的档案还要求申请的 merchantNo 匹配当前主体。最近申请属于 B 时，在 A 的档案中该字段为 false，在 A 提交确认会被拒绝，即使 A 有一份更早的申请。

档案已有主体时，profile.status 表达主体经营状态（PENDING_LICENSE、ACTIVE 或 SUSPENDED）。申请的 PENDING、REVIEWING、REJECTED、APPROVED 从 application.status 读取；待补证主体提交后档案可继续为 PENDING_LICENSE。没有主体时档案可为 NONE、APPLYING、REVIEWING、REJECTED。查看驳回原因后按可编辑状态补交。提交或审核超时先读本人申请和当前档案，避免重复提交。FREE 生效不能确认资质获批、允许上架或启用收款。

## 支付商户进件

所有以下端点要求本店 biz:finance，属主有该权限。纯 LEGACY 客户端可省略选店头，此时默认门店须通过原有财务岗位检查。路径、query 或 body 指定分店时，服务器再核对该分店属于当前主体、门店有效且本人拥有该店财务权；省略所有店号时按默认门店授权。传了 X-Store-No 时，以该店为准。选店头、路径、query、body 中的店号和主体有冲突时，开户、提交、回查均在调用进件服务前拒绝。资金路径 fundsMode 由平台决定。AGGREGATED 的建店、FREE 和补证没有独立渠道进件前置条件；DIRECT 商户按实际通道资格开户。支付商户进件处理订单款项的收款资格，与 Plan 价格、续费和购买相互独立。

| 方法和路径 | 请求与结果 |
|---|---|
| GET /biz/merchant/pay-channel | 返回主体可选通道列表。尚未开通的实际候选通道以 NONE 占位。不要根据示例猜测线上渠道 |
| GET /biz/merchant/payment | 读取进件记录。仅返回该主体默认行和本次授权门店行。storeNo 为 null 或空串均代表主体默认 |
| POST /biz/merchant/payment/store/{storeNo} | 需要独立结算时，先为所选店创建通道占位。可传 `{"payChannel":"<通道码>"}`。重复开户会拒绝，先重读状态 |
| POST /biz/merchant/payment | 传 payChannel、settleAccountType、settleAccount、licenses、contactName、contactPhone。主体默认不传 storeNo，独立结算传已选店号。可选 entityNo 必须一致。返回 PaymentApplymentVO |
| POST /biz/merchant/payment/{payChannel}/refresh | 查询当前通道权威结果，独立结算加 `?storeNo=<selectedStoreNo>`。返回同形对象 |

收款行字段包括 payChannel、channelName、applyStatus、submitted、missing[]、canReceiveMoney、payMerchantNo、subMchidMasked、settleAccountType、settleAccountMasked、rejectReason、appliedAt、activatedAt、storeNo，回显仅有掩码。时间为毫秒或 null。NONE／APPLYING 且尚未提交时按 missing 补材料；submitted=true 且 APPLYING 时等待并回查。REJECTED 展示 rejectReason 后补交；ACTIVE 读 canReceiveMoney，FROZEN 联系平台。UNKNOWN 通道或缺 provider 不显示成功；超时先查已有进件再 refresh，不自动重发开户资料。已 ACTIVE 再提交会被拒。测试用 STUB 可同步变成 ACTIVE，真实渠道以回查为准。

## 单端进入与异常

`GET /biz/stores/{storeNo}/end-access?endCode=BIZ_APP&clientId=biz-app` 仍只检查指定端。endCode 和 clientId 必填，返回 AccessDecision。BIZ_APP 要求 biz:store，APP_POS 要求 biz:receive；clientId 必须匹配端注册。单端状态使用 T05 原序列化：serverNow 是 ISO-8601 字符串，nextBoundaryAt 是同形字符串或 null。与新查询的整数毫秒区分处理。

`POST /biz/stores/{storeNo}/end-sessions` 传 `{"endCode":"BIZ_APP","clientId":"biz-app"}`，成功 data 为 `{value,expiresAt,entitlementVersion}`，expiresAt 是 ISO-8601 字符串。受保护调用携带 `X-Client-Id`、`X-End-Context: <value>`、BTK 及同一选店头。例子中的 clientId 仅适用于匹配注册的环境。短证明到期、切店或版本变化时重新核验，不能把 context 里的 ACTIVE 直接转换为端权限。

所有接口读取 `{code,msg,data}`，成功 code 为 0。宿主业务拒绝也可能使用 HTTP 200 和非零 code。按 HTTP 与 code 一起分流，禁止因普通资格拒绝把用户登出。

| 情况 | 客户端处理 |
|---|---|
| HTTP 401，10401／10402 | 缺少有效账号或会话过期／撤销，清除失效账号凭据并登录 |
| HTTP 400 非规范路径，10400 | 修正请求。10450／10451 发码限流，10454 验证码锁定，10455 无效或已消费；10456／10457 密码错误或未设置 |
| HTTP 403，70006 / FORBIDDEN；或业务 code 10403 | 返回选店核实成员及岗位，保留账号。关闭门店／资格拒绝可返回 40040，读取 data.nextAction。岗位不足不跳购买 |
| HTTP 409，40040 / CONTEXT_REFRESH_REQUIRED | 重新获取当前店的短证明。10409 为宿主操作参数或状态冲突，先读回结果 |
| HTTP 503，10500 / CHECK_UNAVAILABLE | 账号、治理或排期快照暂不可核验，按 RETRY_CHECK 刷新，避免退回默认店继续操作 |
| 70198、70199、70020、10467、10404、70045 | 分别处理未配置经营类型、旧店需迁移、主体门店额度、申请不可编辑、目标不存在／不属该主体、支付通道不可用。按当前数据提示用户 |

## B 端接入工作与运行条件

- `b-app/src/api/contract.ts`、`b-app/src/api/endpoints.ts` 增加五项 GET 和只读 DTO，更新 MerchantProfile.currentStoreNo 等可空字段；若已有共享类型则同步。保留单端查询的两个必填参数与 ISO 时间。
- `b-app/src/stores/merchant.ts` 按会话与店号管理响应。发请求时捕获账号、店号和一次切店序号，返回时仅将匹配序号的结果用于当前屏。切店和登出时清理端证明；查询目录或提交分店表单不临时修改全局选店。
- 登录、选店、申请、资质、收款和 Plan 页分别位于 `b-app/src/pages/{login,stores,apply,qualifications,payment,plan}/index.vue`。类型和三档来自发布目录，当前订阅读实际门店；FREE 的建店提示和付费档的只读价格分开。旧额度页保留原语义。商城、报价、创建付费订单、试用跳转、支付、升级、续费、抵扣和自动迁移均未在本轮交付。
- 启用宿主 api profile 和 `commerce.enabled=true`；新建 STORE_V1 另需 `commerce.store-creation.enabled=true`。默认关闭。使用已迁移的同一主库 DataSource 和 JDBC 事务管理器、DB MERCHANT 会话、`commerce.access.active-key-id`、`commerce.access.keys.<id>` 的部署密钥（Base64 解码至少 32 字节），end-context-ttl 最长 5 分钟。目录只读服务独立于 `commerce.catalog.enabled` 运营写开关。须先通过真实目录配置，发布三档并启用经营类型。上线前需压测有界连接池承载账户锁及独立 fresh 会话事务。已有 STORE_V1 的实例不能通过关闭底层模块绕回旧模式。
- 测试覆盖宿主登录、实际建店事务和公开查询，外部短信送达、银行开户、对象存储与 OCR 未做生产验收。短信接真实渠道需 `shop.sms.stub=false` 和阿里短信签名／OTP 模板凭据；渠道进件需真实商户的 `shop.pay.wechat.enabled`／`shop.pay.alipay.enabled` 与网关配置；`shop.pay.stub=true` 仅用于测试。证照存储按 `shop.media.provider` 配置，COS 凭据由环境注入；视觉识别由 `shop.ai.vision.enabled`、模型与凭据控制。请勿在 B 端内置密钥、测试价或验收记录。
