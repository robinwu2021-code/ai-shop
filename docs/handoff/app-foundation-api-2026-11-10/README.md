# 商户 App 基础能力 API 联调交接（166）

更新：2026-11-10。本包按**实际部署提交**核对，覆盖注册/登录、身份、门店切换与管理、主体、员工、连锁、平台进件、独立PAY进件与认领，以及库存接入边界。不代表零售、服业、餐饮全量业务都已联调完成。

ai-shop API：`5c08fc760b4aba05c15aad4b6a709b99229ce015`；独立near-pay：`5c588723da4593bd80d77b5fe1af24fbd97e102c`；本批App提交：`de57567effcb2872328d349d6177483cb4b1715a`。这些为本地提交，上一轮未推送远端分支；166运行制品由发布清单SHA精确标识，不能用远端分支名代替制品身份。OPS未发布/重启，App未发正式包。


本轮新增[166基础能力验收](../foundation-166-acceptance-2026-11-10/README.md)：真实身份、两店切换、员工停用/恢复及App实际客户端验证已分项记录；[资料修订三API增量](../foundation-166-acceptance-2026-11-10/merchant-profile-api-increment.md)仅本地实现、待发布，不改本包164条固定部署基线。

## 交付物

| 文件 | 给App的用途 |
|---|---|
| [api-catalog.md](./api-catalog.md) | 按模块浏览164个方法/路径、请求作用域与开放状态 |
| [api-catalog.csv](./api-catalog.csv) | 筛选分工；含query字段、Java方法签名、DTO类型和精确源码行 |
| [api-catalog.json](./api-catalog.json) | 可供工具消费的同源清单，固定部署commit |
| [contracts.json](./contracts.json) | 部署版本真实Java record声明及校验注解，不虚构OpenAPI完整schema |
| [postman.collection.json](./postman.collection.json) | 26个核心请求，逐条手动执行；含multipart和私有二进制读取 |
| [postman.environment.template.json](./postman.environment.template.json) | 空环境模板，口令/token/ticket仅本地填；不要回传或提交填值版本 |
| [open-items.csv](./open-items.csv) | HTTPS、真实授权、N1、库存与真机联调的剩余前置 |
| [integration-cases.csv](./integration-cases.csv) | 12组场景、验收断言、前置与当前状态；不是已经全绿的报告 |
| [verification.json](./verification.json) | 本包路径/方法/DTO/示例一致性和两仓镜像校验 |

## 1. 环境与开关

- App始终请求 **ai-shop origin**，不直连near-pay、库存或数据库。166宿主API监听本机18082，独立PAY18093，独立库存18086；这些是服务端端口，不是已确认的公网App地址。不要把支付H5域名自动当作App API origin。
- API/PAY已部署，健康与未认证401探测通过；这只证明启动/鉴权，不代表真实账号、短信送达、商户委托、渠道就绪或付款验收。
- `MERCHANT_API_BASE_URL`：App `main_prod.dart` 使用production ApiConfig，**要求HTTPS**。本包没有确认/新增166的外部HTTPS App入口；正式构建联调前需在私有环境配置确认该origin、TLS和路由前缀。不要使用生产配置连接内网HTTP，也不要关闭正式HTTPS校验。
- Postman/开发机可用已有UME跳板将166 `127.0.0.1:18082`映射到开发机回环，再将本地 `hostOrigin` 设置为 `http://127.0.0.1:18082`（或实际本地映射端口）。SSH密钥与跳板参数沿私有配置；本包不携带凭据。Android调试设备按实际需要使用 `adb reverse tcp:18082 tcp:18082`，同时使用仅调试装配的非production ApiConfig；不能因此修改正式入口校验。此前本地runtime测试依赖合成schema/身份，不可直接把它们改指166运行。
- App编译开关：统一身份选用 `UNIFIED_LOGIN_ENABLED=true`；PAY共享会话/成员路径需 `NEARPAY_BIZ_ONBOARDING_ENABLED=true`；N2需同时 `CHANNEL_ACCOUNT_CLAIMS_ENABLED=true`。`STORE_ENTITY_RELINK_ENABLED`在166维持false。编译开关不是服务端权限，不可据此假定接口放行。
- 服务端本次已开BIZ身份桥/成员转发、PAY草稿/私有附件及N2；**N1新建仍关闭**，且宿主库存开启、门店投影同步原为关闭，前置未补齐。本包不提供开启N1的联调操作。

## 2. 请求头与返回约定

| scope | 请求规则 |
|---|---|
| PUBLIC | 不依赖登录；按请求体验证凭证或读取公开目录 |
| ACCOUNT | `Authorization: Bearer <原btk会话>`，不携旧选店头；身份、全账号目录使用此作用域 |
| ACCOUNT_OWNER | 与ACCOUNT相同，但必须真实USR原单所有人；N1恢复不能借失效旧店头 |
| STORE | ACCOUNT + `X-Store-No`、`X-Entity-No`，值来自当前目录/选择；路径店、体店、头店必须一致 |
| MANAGEMENT | ACCOUNT + API明确的managementNo/brandNo/entityNo等资源坐标；无需借当前门店推导管理空间权限 |
| PAY_MEMBER | 原宿主会话；集合memberships不传PAY商户头，其他PAY申请路径传真实 `X-Merchant-No` |
| STORE_PAY | STORE + `X-Merchant-No`，必须等于可信pay-service `merchantRef`；host finance/foundation与PAY typed成员都要通过 |

scope是联调请求构造约定，最终权限及条件以清单中固定提交的Controller、CommerceRoutePolicy和服务端真实资源授权为准。员工/角色/连锁页不能仅按UI能力推导有权写。不要发送自造的X-Internal-*、用户号、subjectType/subjectRef冒充身份。

普通host成功响应示意：`{"code":0,"msg":"…","data":…}`。PAY原报文为 `{"code":0,"message":"OK","data":…, "requestId":"…"}`；统一按code/data解析，展示消息兼容msg/message，不能按HTTP200直接判成功。私有附件成功为实际Content-Type的二进制，无JSON信封；PNG/JPEG/PDF≤5MiB，依资源鉴权、no-store/nosniff，不保存公开URL。

401/10401按会话失效处理；403/10403/70006按权限/门店作用域处理，不能把员工无权解释为“尚未注册”。10404可能是资源/原回执不存在；10409为业务冲突/版本变化；10474为身份ticket失效；70198提示需有效门店上下文；10505或上游503表示依赖不可用。HTTP与业务code都记录，但日志不得带口令、ticket、token、证明或敏感材料。具体PAY拒绝还含reasonCode，按实际回执展示，不硬编码全部为同一失败。

## 3. 注册、登录、冷恢复与门店切换（先联调）

1. 公共目录 GET `/common/master-data`；手机号/邮箱验证码分别POST `/biz/auth/otp/send`、`/biz/auth/email-otp/send`。发送行为会调用真实通知通道，使用受控账号，不批量跑集合。
2. 推荐统一入口 POST `/biz/auth/identities`：`{grantType:"PHONE_OTP"|"EMAIL_OTP"|"PASSWORD", principal, credential, agreed:true, marketCode:"TW"}`。响应IdentityChoice含ticket、expiresInSeconds、identities、canCreateOwner、currentIdentityNo。此时ticket不是登录token。
3. POST `/biz/auth/identities/enter`：`{ticket,identityNo}`，identityNo照返回原值；新个人身份仅在服务端canCreateOwner允许时使用 `NEW_OWNER`。不另造 `/register`。通过验证码/身份进入按已有账号规则完成注册，PASSWORD不作为陌生账号注册方式。
4. 保存返回token，读取 `data.merchant.subjectKind` / `userNo` / `currentIdentity`。USR是个人账号，MCH是员工；无店MCH仍是员工，不按手机号、token字符串或merchantNo是否为空猜身份。旧 `/biz/auth/login`、`/staff-login`保留兼容，不能与统一票据流程混用。
5. GET `/biz/stores/mine`取分组主体/门店。无店USR可先GET `/biz/merchant/store-setup`、`/biz/business-types`及plans，再POST `/biz/merchant/quick-start`，字段见contracts里的QuickStartReq，市场/行业/子行业选项来自真实目录。
6. 切店是**更新请求上下文**：选择目录中的entityNo/storeNo，重新GET profile、`/biz/context`、`/biz/stores/{storeNo}/context`及features。没有新增 `/switch-store`；设置默认店不等于本次切店，不调用 `/default`充当切换。
7. 切店/切身份时丢弃迟到响应；缓存键含环境、typed身份、主体和门店。统一身份mine返回切换票据；进入其他身份可能撤销原source会话，不撤销其他独立sibling会话。冷启动用现有SessionManager恢复，再读profile/目录；无refresh合同，不自造refresh接口。

## 4. 主体、门店、员工与连锁

- 主体：GET `/biz/entities`返回审核增强视图，GET `/biz/entity/{entityNo}`返回 `{entity,stores}`，不要把整个响应直接当entity。列表/详情含review/applyNo/rejectReason等实际字段。
- 门店：`/biz/store`、`/biz/store/list`、`/biz/store/create`及rename/status/default/categories等方法见清单；修改按当前store/entity及biz权限，不靠仅本地筛选保护跨店。`/biz/store/{storeNo}/pay-setting`只维护线下付款/COD开关，不是独立PAY渠道绑定。
- 员工/角色：`/biz/staff`、`/biz/roles`等按真实门店权限验收；停用或撤店授权后，原会话不能继续访问该店。全量字段和方法见对应record声明/签名。
- 连锁：先GET `/biz/management/context`，无管理空间时按真实权限POST `/biz/management/spaces`，不能把当前店主体号直接当managementNo。实体/门店/品牌目录支持cursor/size；读取返回nextCursor直至为空。
- 支付候选：GET `/biz/management/entities/{entityNo}/payment-merchants?managementNo=…`，可选payChannel/payScene/storeNo；候选按真实数据范围/资格产生，不能拿商户名相同或掩码相同自行匹配。
- 品牌加入/切换/退出：先POST `/biz/stores/{storeNo}/brand-changes/check`，从返回保存checkToken和各revision；再POST `/brand-changes`，提交真实expectedStoreRevision/expectedScopeRevision/品牌revision及reason。退出toBrandNo用实际合同空值，不硬造“默认品牌”。管理写使用稳定 `Idempotency-Key`；查GET `/biz/management/brand-changes/{changeNo}`恢复，未知不能换key。完整请求见ManagementModels records。OPS未发布不等于BIZ管理API不存在。

## 5. 平台资料 N3 / 审核回显 N4

POST `/biz/merchant/onboarding`，STORE+biz:finance，真实USR店主且主体PENDING_LICENSE。体为 `{entityNo,storeNo,profile,licenses,agreed:true}`；profile含subject/name/taxNo/owner/ownerPhone/industry/industryNote。主体类型是COMPANY/SOLE/PERSONAL；非PERSONAL税号必须8位数字，OTHER行业需说明，ownerPhone为E.164格式。

PERSONAL licenses为空；COMPANY/SOLE必须各一张对应COMPANY_REGISTRATION/BUSINESS_REGISTRATION，assetKey必须来自该主体平台私有资质上传且满足 `<entityNo>/_ENTITY/qual/...`。这是平台营业资料；PAY渠道结算/证照另走私有流程。不提交settleAccount或非空channels。

返回entityNo/applyNo/applyStatus/agreedAt/replayed/revised/nextStep。`nextStep=PRIVATE_CHANNEL_ONBOARDING`只是导航，不代表已创建PAY来源/成员。N4回GET entities/detail展示真实审核状态与rejectReason。REVIEWING不可编辑、驳回后行为和原单修改按后端状态执行，不本地假审批。

## 6. 台湾支付来源 → 独立PAY进件 → N2

1. 当前门店GET `/biz/merchant/pay-service`，读取NONE/PENDING/NEED_MORE/REJECTED/APPROVED及merchantRef等事实；需要申请来源时POST `/biz/merchant/pay-service/apply`，仅平台商户资料Form，不含结算/渠道私密材料。
2. GET `/biz/pay/v2/memberships`核对typed成员，PAY商户号来自merchantRef及memberships，**不是**host entityNo。无委托按无权限处理；只有store.bind的成员可做N2，但不据此允许onboarding.apply/query。
3. 进件15路由见清单，所有请求经host代理。草稿save/confirm传 `requestNo,expectedVersion,expectedDraftVersion,fields`；每次用真实回执中的新版本。材料封存返回引用，私有附件鉴权回读；实际可填字段仍按本次部署版本字段合同，不把别会话V78/V80/新蓝新运行时资料当已发布。取消请求用原requestNo、expectedVersion、reason；取消UI/恢复状态按原合同。
4. N2全部9个方法见下表。要求host当前finance/foundation/source映射一致，PAY成员actions含store.bind。App界面首批NEWEBPAY；实际enabledChannels/market仍由PAY判断。商户号/subMID≤64字符，App不收或传渠道密钥credential。

| 方法 | 路径（前缀 `/biz/pay/v2/channel-account-claims`） | 形状 |
|---|---|---|
| POST | 集合 | `{requestNo,payChannel,channelMerchantId,subMerchantId?}`→BizView |
| GET | 集合 | 可选state→BizView数组 |
| GET | `/by-request/{requestNo}` | 原CREATE的CommandReceipt |
| GET | `/{claimNo}` | 当前BizView |
| POST | `/{claimNo}/files` | multipart requestNo/expectedVersion/file→BizView |
| GET | `/{claimNo}/files` | 私有文件元数据数组 |
| GET | `/{claimNo}/files/{fileNo}` | 鉴权二进制 |
| POST | `/{claimNo}/submit` | `{requestNo,expectedVersion}`→BizView |
| GET | `/{claimNo}/commands/{requestNo}` | 原上传/submit的CommandReceipt |

BizView含claimNo/requestNo/merchantNo/payChannel/channelMerchantMasked/state/version/files/reason/payMerchantNo/accountVerificationStatus/accountStatus；不返回完整MID。状态DRAFT→PENDING_VERIFY→LINKED/REJECTED。每次上传更新version；提交锁定有效附件。LINKED仅为运营关联，账户核验/启用、门店绑定及readiness仍分别判断；当前App的storeBindingConfirmed/collectReady保留false，不能改为“审核通过即可收款”。

**原键恢复**：发写请求前持久化原requestNo+动作+版本+作用域+内容摘要；不持久化密钥、MID明文或证明字节。超时/503/断网后先查原CREATE或command；404只表示尚无回执，保留原键。`{requestNo,outcome:"SUCCEEDED",result}`接收result；`outcome:"REJECTED",failure:{code,reasonCode,message}`确认业务拒绝并结束原日志，用户修正后另开新意图。身份/基础设施错误不等于记录的业务拒绝。不同用户/门店/PAY商户切换时丢弃迟到结果，不自动替换目标重试。

## 7. N1 与库存边界

N1 GET `/biz/store/{storeNo}/entity/relink-check?entityNo=<目标>`可做只读诊断，路径/头当前店一致；POST同门店`/entity`虽已实现，但166新建关闭，不放执行按钮。原ACCOUNT所有人可GET `/biz/merchant/store-claims/by-request/{requestNo}`、GET `/{claimNo}`、POST `/{claimNo}/resume`。GET无推进；LOCAL_COMMITTED后只向前恢复，不能用超时/TTL当解冻条件。只有COMPLETED才更新选店目录。不得为联调伪造业务单或开启新建。

库存App业务请求继续走清单中的host `/biz/inventory/*`。这些是当前既有宿主库存路径，不代表本次独立V27服务接入；独立inventory-test只有启动/数据库/鉴权验收，尚无受控SHOP凭据与指定owner，未切writer、未开调度。不能把inventory admin token放App，不能让App直连18086；后续需先完成owned档案/系统凭据/服务端客户端接线和指定业务验收，再另行更新清单状态。

## 8. App联调执行与交回材料

按 [integration-cases.csv](./integration-cases.csv) 执行：先F01～F04身份/切店，再F05平台资料，F06真实PAY成员，F07～F09认领，F11连锁；F10仅验证关闭/诊断；F12只排查现有host读，独立库存接入另排。

前置清单：确认App可访问的HTTPS origin或受控调试隧道；私下配置店主/员工账号；为受控商户配置真实typed PAY成员和store.bind/onboarding权限；指定测试门店/管理空间、其真实授权和渠道目录。用户口令、Bearer和服务凭据不写聊天/交接文件。本包未自动执行任何写请求，未发验证码或新建166业务数据。

回交每例：caseId、App构建commit/开关、环境、脱敏actor类型与门店/主体/PAY商户标识、方法/路径、HTTP状态+业务code/reasonCode、requestId/traceId、原requestNo和version、预期/实际、是否清理测试数据。截图及日志须脱敏，不含令牌、密码、材料或渠道号明文。部署证据、166业务验收、真机验收分开标记，未执行就保持待验收。

新批基础能力部署以[166经营主体与支付资料发布记录](../foundation-release-166-2026-11-10/README.md)为准；本包164路由仍固定旧制品快照，新增合同按独立增量交接。
