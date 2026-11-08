# TDD-商户App商户资料提交

状态：草稿（2026-11-08，待产品与商户 App 确认；未实现、未跑任何测试）
档位：**2**。三个接口里 N3（一次提交资料）单看是 1 档（新端点、复用已有权限码、ai-shop 不加表）；
N1（门店改挂主体）改门店与主体的关系、牵动 near-pay 门店投影与商城订阅，N2（绑定已有渠道商户号）决定商户渠道凭据从哪条路进系统 —— 两者都不可逆，按 2 档写 §3 §4。**需要两份 ADR（本文不写）**：「门店改挂主体（占位认领）」「商户自带渠道商户号的录入路径」。
关联需求：
- 商户 App 交接文档 [`docs/交接-商户App功能矩阵与API.md`](../交接-商户App功能矩阵与API.md) §3 通用约定、第 4 模块「商户与进件」（4-05 / 4-06 / 4-07）、附录 B 第 4 行
- [入驻进件建店-需求与四层对齐](../requirements/入驻进件建店-需求与四层对齐.md)（入驻 / 进件 / 建店三段独立；4.2.6 门店切换主体）
- [账号主体门店-关系定稿](./design/账号主体门店-关系定稿.md) §3（门店可切换主体、六条硬约束）
- [TDD-运营端申请支付服务](./TDD-运营端申请支付服务.md) §7（B 端 `pay-service` 申请与 foundation 通道）
- [TDD-入驻分阶段与进件简化](./TDD-入驻分阶段与进件简化.md) §5.2（无照个人能否开店）

依据代码：`origin/codex/ai-shop-codex` 之上的 `codex/bapp-handoff-tdd`（`fc070b2d4`）；near-pay 依据本机 `near-pay` 仓库（`993cf6b49`）。App 依据本机 `ai-shop-bapp`（`d10b557`）的 `apps/merchant_app/lib/features/merchant/merchant_api.dart`、`store_payment_page.dart`、`dev/mock/mock_v5.dart`。
创建：2026-11-08 · 最后更新：2026-11-08

---

## 一句话（L1）

商户 App 的「商户资料」三条建议接口，后端的最终建议是：**N3 一次提交 = 一个薄编排端点**，把已有的「入驻申请 + 同意协议 + 申请支付服务」串成一次调用，结算账户只透传给 near-pay、ai-shop 不落库；**N2 绑定已有商户号 = 提交一张待运营核实的绑定申请**，不收密钥；**N1 门店改挂主体 = 只做「占位主体门店认领本账号已有主体」**，不做通用的换主体。

## 结构（L2）

| 接口（App 建议） | 本文建议 | 能否用已有接口组合 | 档 | ai-shop 新增 | 依赖 near-pay |
|---|---|---|---|---|---|
| N3 `POST /biz/merchant/onboarding` | 保留路径，改请求/响应细节 | **大半能**：资料、证照、协议、申请支付服务都有现成接口；结算账户没有去处 | 1 | 新控制器 + 编排服务；无表 | `source-links/apply` 的 `profile` 加 `settlement`（透传） |
| N2 `POST /biz/merchant/payment/bind` | 保留路径；去掉 `credential`，加 `requestNo`；结果是「待核实」不是「已开通」 | 不能：ai-shop 没有；near-pay 只有运营端建渠道账户 | 2 | 端点 + `mch_payment_merchant` 加两列（V6xx 待定） | 新内部端点「渠道账户认领」 |
| N1 `POST /biz/store/{storeNo}/entity` | 保留路径；只允许「占位主体门店 → 本账号 ACTIVE 主体」 | 不能：`/biz/store/{storeNo}/payment` 只能在本主体内换收款号 | 2 | 端点 + 改挂服务 + 两个只读守卫端口；无表 | 无（依靠门店同步把投影映射到新主体） |

三条的共同点：都是**主体级**动作。N2、N3 要主体级收款目标（`FoundationTarget`），必须走 `CommerceRoutePolicy` 的 foundation 通道，**不能**登记在 `ShopStoreMemberRoutes` —— 11/07 在 166 上就是因为把 `pay-service` 登记错了地方，新模型门店一直 70006（见 §1 第 10 行）。N1 是门店级动作，与 `POST /biz/store/{storeNo}/payment` 同类，登记在 `ShopStoreMemberRoutes`。

---

## §0 对账一 · 需求 → 设计

需求来自交接文档 4-05 / 4-06 / 4-07 与 App 的 mock 行为（`mock_v5.dart` 486–537 行）。App 只认 `code=0`，响应体一律丢弃（`merchant_api.dart` 268–329 行三处都是 `(_) => true`），所以本文对响应体的设计不破坏 App 现状。

### N3 一次提交商户资料（v5 C04–C07、M04）

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| A1 | Given 店主选中一家挂在自己 `PENDING_LICENSE` 占位主体下的门店 When `POST /biz/merchant/onboarding` 资料齐全 Then 生成一张 `PENDING` 入驻申请，`entity_no` 预填为该占位主体；`GET /biz/merchant/apply` 读得到；主体类型按 `COMPANY→ENTERPRISE`、`SOLE→INDIVIDUAL`、`PERSONAL→MICRO` 落权威码 | `MerchantOnboardingService#submit` → `OpsService#createMerchantApply(cmd, entityNo)`；映射在 `MerchantOnboardingRequests#subject` |
| A2 | Given `agreed=true` When 提交 Then 该申请的 `agreed_at` 有值，响应带 `agreedAt`；Given `agreed=false` Then 10400，**什么都不写** | 编排里先校验再写；`OpsService#acceptAgreement(userNo, entityNo)` 与建申请同一事务 |
| A3 | Given 主体类型 When 缺该类型的必传证照，或 `COMPANY`/`SOLE` 的统编不是 8 位数字，或帐号不是 10–16 位数字 Then 10400，不写库、不调 near-pay | `MerchantOnboardingRequests#validate`（纯函数） |
| A4 | Given 台湾市场且独立部署了支付服务 When 本地写入成功 Then 用同一份资料调 near-pay `source-links/apply`（法定名称 = `name`、统编 = `taxNo`、负责人 = `owner`、电话、地址、证照作 `materials`、结算账户作 `settlement`），响应带 `payService{status, merchantRef, applicationRef}` | 复用 `PayServiceApplyRequests.build` + `PaySourceLinkApi#apply`；`Form` 加可选字段 |
| A5 | Given 任何时候 When 提交 Then 结算帐号明文**不落 ai-shop 库、不进日志、不进事件号摘要**，响应只回掩码 | `SettleAccountInput#toString` 掩码；`PayServiceApplyRequests.digest` 不含结算字段 |
| A6 | Given near-pay 不可用（内嵌部署或调用失败） When 提交 Then 本地申请与协议照常提交，HTTP 200，`payService.status=UNAVAILABLE`；When 原样重发 Then 不新建第二张申请，只补发 near-pay | 编排先本地事务后远程调用；重放判据见 §2 N3「幂等」 |
| A7 | Given 已有一张进行中（`PENDING`/`REVIEWING`）的申请 When 资料不同 Then 10409；资料相同 Then 视为重放（A6） | `OpsServiceImpl` 已有的「一人一份进行中申请」唯一键 + 编排层比对 |
| A8 | Given 门店所在主体已不是占位主体（已认证 / 被驳回后重提除外） When 提交 Then 10409，提示改走「申请开通收款」 | `MerchantPortImpl#requirePendingLicenseOwner` 已有的判断（抛 10403，编排层先判、给 10409） |
| A9 | Given 新模型（STORE_V1）门店的店主 When 调 N3 Then 通过门店准入（不是 70006），权限码 `biz:finance` | `CommerceRoutePolicy` foundation 通道加 `POST /biz/merchant/onboarding` |
| A10 | Given 请求体 `entityNo`/`storeNo` 与 `X-Entity-No`/`X-Store-No` 不一致 When 提交 Then 拒绝（`AccessDenied(FORBIDDEN)`，经 `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/CommerceAccessErrors.java` 回包，与 `pay-service/apply` 同一行为） | `FoundationPaymentAccess#require` + `FoundationTarget#check` |

### N2 绑定已有的渠道商户号（v5 C09）

| AC | 需求 | 落点 |
|---|---|---|
| B1 | Given 主体已向 near-pay 申请过支付服务（`GET /biz/merchant/pay-service` 不是 `NONE`） When `POST /biz/merchant/payment/bind {payChannel, channelMerchantNo, requestNo}` Then near-pay 记一张「待核实」的渠道账户认领；ai-shop 该渠道行变 `APPLYING`、`source=BIND`，只存商户号掩码 | `MerchantChannelBindService#bind` → `PaySourceLinkApi#claimChannelAccount`（near-pay 新端点）；`mch_payment_merchant` 新列 |
| B2 | Given 渠道不在本市场启用列表（或是 `JKOPAY`） When 绑定 Then 70045 | `PayChannelMasterPort#enabledChannels` |
| B3 | Given 该主体该渠道已有 `ACTIVE` 行或在途申请 When 绑定 Then 10409 | 绑定服务先查 `mch_payment_merchant` |
| B4 | Given 主体还没申请支付服务 When 绑定 Then 10409（先走 N3 或 `pay-service/apply`） | 同上，查 `PaySourceLinkApi#status` |
| B5 | Given 运营在 near-pay 核实通过（已有 `POST /pay-ops/v2/channel-accounts/{payMerchantNo}/verify`） When 店主刷新 `POST /biz/merchant/payment/{payChannel}/refresh` Then 该行 `ACTIVE`，带 `payMerchantNo` | `MerchantPaymentServiceImpl#refresh` 对 `source=BIND` 行改查 near-pay 认领状态 |
| B6 | Given 请求带 `credential` When 绑定 Then 10400（v1 不经 App 收密钥，见待决 3） | 请求 record **显式保留** `credential` 字段、非空即 10400 —— 不能指望 Jackson 拒未知字段（本仓库只在个别控制器手动开了 `FAIL_ON_UNKNOWN_PROPERTIES`，默认是静默丢弃，密钥会被悄悄吞掉而 App 以为绑上了） |
| B7 | Given 新模型门店 When 调 N2 Then 不是 70006 | `CommerceRoutePolicy` foundation 通道 |

### N1 门店改挂到另一个主体（v5 C02）

App 只在「本店还没有已审核的商户资料」时打开 C02（`store_payment_page.dart` 18–25 行 `unlinked`：本店主体 `review == none` 且没有能收款的渠道），这正是快速开店建出来的 `PENDING_LICENSE` 占位主体。本文据此把 N1 收窄为「认领」。

| AC | 需求 | 落点 |
|---|---|---|
| C1 | Given 门店挂在本账号的 `PENDING_LICENSE` 占位主体下，目标主体是本账号当店主的 `ACTIVE` 主体 When `POST /biz/store/{storeNo}/entity {entityNo}` Then `mch_store.entity_no` 改为目标，`is_default=false`，`pay_merchant_no` 清空（回到目标主体默认号），写一条 critical 审计（谁、何时、A→B） | `StoreRelinkServiceImpl#relink` |
| C2 | Given 目标主体不是本账号店主的 When 改挂 Then 10403；目标不是 `ACTIVE` Then 10409 | `MerchantEntityService#requireOwned` + 状态判断 |
| C3 | Given 门店当前主体不是占位主体（已认证主体之间互换） When 改挂 Then 10409 —— v1 不做通用换主体 | 同上 |
| C4 | Given 门店有任何订单、线下收款、储值或已付费的订阅期，或占位主体已向 near-pay 申请过支付服务 When 改挂 Then 10409 | `StoreRelinkGuardPort`（订单域、商城订阅各一个实现）+ `PaySourceLinkApi#status` |
| C5 | Given 改挂成功 Then 该门店的员工授权（`mch_store_role`，店主除外）全部撤销，响应带 `revokedStaff` 数；占位主体下已无门店时逻辑删除占位主体 | 同一事务 |
| C6 | Given 改挂成功 Then 触发门店同步，near-pay 的门店投影按新主体映射 | `payMirror.changed` / 门店同步（与 `setPayment` 同一调用） |
| C7 | Given 已经挂在目标主体 When 重发 Then 200，结果同上一次（幂等） | `relink` 开头短路 |
| C8 | Given 新模型门店 When 调 N1 Then 不是 70006，权限码 `biz:store:admin` | `ShopStoreMemberRoutes` 加 `POST /biz/store/{id}/entity` |

**孤立项**
- 没落点的 AC：
  - 交接文档 4-01 主体列表要的 `taxNo`、`owner`、`rejectReason`、`subject`，后端 `EntityVO` 没有（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/EntityVO.java`，只有 `entityNo, name, status, verified, legalForm, storeCount, isPrimary, canManage`）。C02 选主体时 App 靠 `review` 判断，读不到就全部当成「无审核」——**N1 的选择列表在真实服务上会是空的**。不在本文三接口范围，记在这里，建议并入《TDD-商户App接口字段对齐》。
  - 签约 `signRequired` / `signUrl`：near-pay 进件单有 `signUrl`（near-pay `backend/pay/pay-portal/src/main/java/ai/neargo/shop/pay/api/ops/v2/OpsOnboardingDtos.java` 20–23 行），ai-shop 没有转出来。不在本文范围。
- 挂不上 AC 的设计：
  - App 的 N3 `channels[].channelMerchantNo`（「新建资料时顺便绑已有商户号」）：本文不在 N3 里收，改为 N3 成功后逐个调 N2。理由见 §2 N3「与 App 的差异」。

---

## §1 现状与影响面

### 已有能力（每行都有出处；路径相对仓库根）

| # | 能力 | 出处 | 与本文的关系 |
|---|---|---|---|
| 1 | 提交入驻申请（自填），申请挂到所选门店的占位主体 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java` 310 行 `POST /biz/merchant/apply` → `backend/shop-core/src/main/java/ai/neargo/shop/platform/impl/OpsServiceImpl.java` 503 行 `createMerchantApply`；占位认领 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java` 1512 行 `merchantApplicationEntity`、1543 行 `requirePendingLicenseOwner` | N3 资料部分直接复用 |
| 2 | 一人一份进行中申请（先查 10409，唯一键 `uk_apply_active_owner` 兜底） | `OpsServiceImpl.java` 531–536 行 | N3 幂等与 A7 |
| 3 | 有执照的主体类型必须带 `BUSINESS_LICENSE` 结构化资质 | `OpsServiceImpl.java` 719 行 `requireLicenseIfNeeded`（只认 `type=BUSINESS_LICENSE`） | N3 证照映射必须产出这一项 |
| 4 | 主体类型权威码与旧码 | `backend/shop-app/src/main/resources/db/migration/V2__seed_master_data.sql` 14–17 行：`MICRO`(旧 `PERSONAL`)、`INDIVIDUAL`(旧 `INDIVIDUAL_BIZ`)、`ENTERPRISE`(旧 `COMPANY`)；`backend/shop-core/src/main/java/ai/neargo/shop/platform/impl/MasterDataServiceImpl.java` 113 行 `canonicalSubject` 按码或旧码翻译 | App 的 `COMPANY`、`PERSONAL` 能被翻译，**`SOLE` 不能**（会原样落库成脏值） |
| 5 | 同意商户协议（作用于最近一张申请，只能本人） | `BizMerchantController.java` 302 行 → `OpsServiceImpl.java` 645 行 `acceptAgreement(userNo, selectedEntityNo)` | N3 的 `agreed` |
| 6 | 申请支付服务（near-pay 建平台商户 + 来源链接 + 门店关联），同资料重复提交幂等 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPayServiceController.java` `POST /biz/merchant/pay-service/apply`；组装 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayServiceApplyRequests.java`（事件号 = 主体 + 版本 + 资料摘要）；客户端 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PaySourceLinkApi.java` | N3 收款部分直接复用；`Profile` 已有 `industry`、`materials` 两个字段，ai-shop 目前传 `null` / 空表 |
| 7 | 申请状态 `NONE/PENDING/NEED_MORE/REJECTED/APPROVED` | `BizPayServiceController.java` `GET /biz/merchant/pay-service` | N2 前置判断、N1 守卫 |
| 8 | near-pay 接收 `materials[{kind, ref}]` | near-pay `backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/platmch/InternalSourceLinkController.java` 82–87 行 | N3 证照可直接走 materials |
| 9 | 渠道进件 / 补件（结算帐户、证照） | `BizMerchantController.java` 788 行 `POST /biz/merchant/payment` → `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantPaymentServiceImpl.java` 162 行 `submit` | **台湾不可用**：唯一的进件网关实现是 `StubApplymentGateway`，没有网关时抛 70045（`MerchantPaymentServiceImpl.java` 498–514 行）。所以 N3 的结算帐户不能借这条路 |
| 10 | 主体级收款类路由走 foundation 通道（绑定 `FoundationTarget`） | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` 122–160 行（`/biz/merchant/payment`、`pay-channel`、`pay-service*`、`/biz/merchant/apply`、`agreement/accept`、`/biz/qualifications*`、`/biz/upload/image`）；注释写明「申请支付服务不在这里」见 `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | N2、N3 照此登记 |
| 11 | 门店换收款号（只能挑本主体 `ACTIVE` 的号，门店专属号不能给别的店） | `BizMerchantController.java` 973 行 `POST /biz/store/{storeNo}/payment` → `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreAdminServiceImpl.java` 297 行 `setPayment`；路由在 `ShopStoreMemberRoutes` 「本店开通收款」行 | N1 的同类先例（权限码、路由、留痕、`payMirror.changed`） |
| 12 | 判断「这个主体是不是我当店主的」，不是就 403、不回落 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantEntityServiceImpl.java` 98 行 `requireOwned` | N1 目标主体校验 |
| 13 | 主体列表 / 详情（只给店主） | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizEntityController.java` `/biz/entities`、`/biz/entity/{entityNo}`（`biz:store:admin`） | C02 选择列表的数据源 |
| 14 | 资质列表 / 保存 / 识别 | `BizMerchantController.java` 535、580 行；`/biz/qualifications/recognize` 在 `BizCertController` | N3 不直接调，App 拍照识别仍用它 |
| 15 | 上传图片（`bizType=QUAL`） | `backend/shop-channel/src/main/java/ai/neargo/shop/channel/media/api/BizUploadController.java` 89 行 | N3 的证照先传图拿 URL |
| 16 | 结算 / 提现收款账户（加密存、只回掩码、运营审核） | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizPayoutAccountController.java` 40、52 行；表 `V358__supplier_payout_account.sql` | **不适合 N3**：它是自营模式下平台代付货款的卡，要求户名 = 主体名（三流一致）；台湾渠道直清的结算帐户要进渠道进件资料。另：该路由**两处都没登记**，新模型门店上会 70006 |
| 17 | near-pay 渠道账户只能由运营建、核实、启用 | near-pay `backend/pay/pay-portal/src/main/java/ai/neargo/shop/pay/api/ops/v2/OpsChannelAccountController.java`（`/pay-ops/v2/channel-accounts`，`POST`、`/{payMerchantNo}/verify`、`/activate`）；商户端 `.../api/biz/v2/BizChannelAccountController.java` 只能列表和把已有账户绑到门店 | N2 后端没有商户自助入口 |
| 18 | near-pay 门店来源主体变了**不自动换关联**，只记 `ENTITY_CHANGED`，换关联只走运营 | near-pay `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/platmch/store/StoreProjectionService.java` 32、202–216 行 | N1 必须限定在「门店从未在 near-pay 关联过」 |
| 19 | 商城订阅账户按门店唯一、带 `entity_no` | `backend/commerce/commerce-persistence/src/main/resources/db/commerce/migration/V3__commerce_subscription_credit.sql` 2–18 行 `mch_subscription_account` | N1 改挂要么同步改，要么拦（本文选拦已付费、同步改 FREE，见 §4） |
| 20 | 新模型门店准入测试、端点权限登记测试 | `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/StoreScopedDailyRoutesFlowTest.java`（`payServiceApplyPassesAdmission` 等）；`backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` 689–690 行 | §5 测试沿用 |

**后端没有**：一次提交资料的端点；绑定已有渠道商户号的任何入口（ai-shop 与 near-pay 商户端都没有）；门店换主体的任何入口（`入驻进件建店-需求与四层对齐.md` 277 行「换主体 ⬜」，`门店注册到进件-流程节点与代码对齐.md` 158 行「产品明确暂不做」）；`ID 证件`类资质码（`qual_type` 值域只有 `BUSINESS_LICENSE/FOOD_PERMIT/FOOD_WORKSHOP/OTHER`，见 `V202__auth_code_qual_type.sql` 11 行）。

### 建议接口能不能用已有接口组合

**N3：大半能。** 如果产品不想要新端点，App 按下表串三个已有接口即可交付除结算帐户外的全部内容：

| App 字段 | 已有接口与字段 | 备注 |
|---|---|---|
| `profile.name` | ① `POST /biz/merchant/apply` `name`；③ `pay-service/apply` `legalName` | |
| `profile.subject` | ① `subject` | **`SOLE` 要改送 `INDIVIDUAL`**（或旧码 `INDIVIDUAL_BIZ`），`COMPANY`/`PERSONAL` 可原样送 |
| `profile.taxNo` | ① `qualificationItems[{type: BUSINESS_LICENSE, code: taxNo, imageUrl: 登记证照片}]`；③ `registrationNo` | apply 没有统编字段，只能放在资质的证号上 |
| `profile.owner` / `ownerPhone` | ① `contactName` / `contactPhone`；③ `representativeName` / `contactPhone` | |
| `profile.address` | ③ `registeredAddress` | apply 没有地址字段 |
| `profile.industry` | ① `industry` | 决定可选主体类型（`requireSubjectAllowedByIndustry`） |
| `licenses[COMPANY_REGISTRATION\|BUSINESS_REGISTRATION]` | ① `qualificationItems` 的 `BUSINESS_LICENSE` 一项 | |
| `licenses[OWNER_ID_FRONT\|OWNER_ID_BACK]` | ① `licenses[]`（纯图片 URL 列表） | 没有证件类资质码 |
| `agreed` | ② `POST /biz/merchant/agreement/accept`（必须在 ① 之后，它作用于最近一张申请） | |
| `channels[]` | 无 | near-pay 申请不分渠道，按市场开 |
| `settleAccount` | **无** | 见 §1 第 9、16 行 |
| `entityNo` / `storeNo` | ① 体内 `entityNo`、`storeNo`（与门店头一致校验） | |

**本文仍建议新增 N3 端点**（§3 方案 A），理由：结算帐号明文只该出现在一次请求里，拆给 App 串接就要 App 持有并多次发送；`SOLE` 映射、证照拆分（营业登记进资质、证件进图片列表）是服务端规则，放 App 会和 C 端入驻走偏；三步之间失败时（例如申请成功、near-pay 失败）由服务端给一个统一的「哪步成了」比 App 自己拼更可靠。组合方案作为后备写在待决 7。

**N2：不能。** ai-shop 没有；near-pay 只有运营端建渠道账户（§1 第 17 行）。

**N1：不能。** `POST /biz/store/{storeNo}/payment` 只在本主体已开通的号里换（`StoreAdminServiceImpl.java` 297–323 行），跨主体会被 10400 拦。

### 会被改到的（已在跑的功能）

- `PayServiceApplyRequests.build` / `Form`：加可选字段，**事件号摘要保持只算原来的五个字段**，否则已提交的主体重发会得到新事件号（被当成资料变更）。
- `MerchantPaymentServiceImpl#refresh` / `#list` / `#availableChannels`：多认一种 `source=BIND` 的行。
- `CommerceRoutePolicy`（commerce starter）、`ShopStoreMemberRoutes`：各加路由。

### 明确不受影响的

- C 端入驻 `POST /mp/merchant/apply`、运营代填 `createApplyOnBehalf`、运营审核 `activate`：N3 只是又一个调用 `createMerchantApply` 的入口。
- 旧进件 `POST /biz/merchant/payment` 的状态机：N2 的行用 `source` 区分，`APPLY` 行逻辑不变。
- 运营端 `POST /ops/merchant/{entityNo}/pay-service/apply`：共用组装函数，只是新字段为空。

---

## §2 方案

### 契约变更总表

- 端点：新增 `POST /biz/merchant/onboarding`、`POST /biz/merchant/payment/bind`、`POST /biz/store/{storeNo}/entity`
- 库表：`mch_payment_merchant` 加 `source`、`channel_merchant_masked` 两列（`V6xx__payment_merchant_bind_source.sql`，号待定）。N1、N3 不加表
- 权限码：不新增。N3、N2 用 `biz:finance`，N1 用 `biz:store:admin`
- i18n：不新增错误码（全部复用 10400/10403/10404/10409/10505/70045），无新词条
- 配置项：无
- 跨服务契约（near-pay，须 near-pay 侧另立 TDD）：`POST /internal/pay/v2/source-links/apply` 的 `profile` 加可选 `settlement`；新增 `POST /internal/pay/v2/source-links/channel-account-claims` 与查询

### N3 `POST /biz/merchant/onboarding`

| 项 | 值 |
|---|---|
| 头 | `Authorization`、`X-Store-No`、`X-Entity-No`（必带，门店范围） |
| 权限码 | `biz:finance`（`@PreAuthorize("@perm.canBiz('biz:finance')")`，与 `pay-service/apply`、`/biz/merchant/payment` 同码） |
| 路由登记 | **`CommerceRoutePolicy` foundation 通道**：`case "POST /biz/merchant/onboarding" -> "biz:finance"`。**不登记 `ShopStoreMemberRoutes`**（登记在那里不会绑定 `FoundationTarget`，新模型门店会 70006 `CONTACT_ADMIN`，同 11/07 事故） |

请求：

```json
{
  "entityNo": "E…",               // 可空；给了必须等于 X-Entity-No
  "storeNo": "S…",                // 可空；给了必须等于 X-Store-No
  "profile": {
    "subject": "COMPANY|SOLE|PERSONAL",
    "name": "…",                  // ≤128
    "taxNo": "12345678",          // COMPANY/SOLE 必填 8 位数字；PERSONAL 忽略
    "owner": "…",                 // ≤64
    "ownerPhone": "+886912345678",// E.164
    "address": "…",               // ≤256
    "industry": "…"               // 行业码，必填
  },
  "licenses": [{"type": "COMPANY_REGISTRATION|BUSINESS_REGISTRATION|OWNER_ID_FRONT|OWNER_ID_BACK", "imageUrl": "…"}],
  "channels": ["NEWEBPAY", "LINEPAY"],   // 见差异 3
  "settleAccount": {"bankCode": "812", "accountName": "…", "account": "1234567890"},
  "agreed": true
}
```

必传证照：`COMPANY` = `COMPANY_REGISTRATION + OWNER_ID_FRONT + OWNER_ID_BACK`；`SOLE` = `BUSINESS_REGISTRATION + 两面证件`；`PERSONAL` = 两面证件（与 App `MerchantDraft.licenseTypes` 一致）。`imageUrl` 必须是 `/biz/upload/image` 返回的本平台地址。

服务端映射（`MerchantOnboardingRequests`，纯函数）：

| 去向 | 来源 |
|---|---|
| `SubmitApplyCommand.subject` | `COMPANY→ENTERPRISE`、`SOLE→INDIVIDUAL`、`PERSONAL→MICRO` |
| `qualificationItems` | 一项 `{type: BUSINESS_LICENSE, code: taxNo, imageUrl: 登记证 URL, expireAt: null, issuer: null}`（`PERSONAL` 没有这一项） |
| `licenses`（图片列表） | 全部证照 URL |
| `contactName` / `contactPhone` / `industry` / `name` | `owner` / `ownerPhone` / `industry` / `name` |
| near-pay `profile` | `legalName=name, registrationNo=taxNo, representativeName=owner, contactPhone=ownerPhone, registeredAddress=address, industry=industry, materials=[{kind: 证照类型, ref: imageUrl}], settlement={bankCode, accountName, accountNumber}` |

处理顺序：校验（A3）→ 本地事务（建申请或判定重放、同意协议）→ 提交后调 near-pay（A4/A6）。`PERSONAL` 没有统编，不调 near-pay（`payService.status=NOT_ELIGIBLE`，见待决 5）。

响应 `data`：

```json
{
  "entityNo": "E…",
  "applyNo": "MA…",
  "applyStatus": "APPLYING",             // 与 /biz/merchant/payment 同口径（PENDING 在端上叫 APPLYING）
  "agreedAt": 1762560000000,
  "replayed": false,
  "settleAccountMasked": "812 ****7890",
  "payService": {"status": "PENDING|NEED_MORE|REJECTED|APPROVED|UNAVAILABLE|NOT_ELIGIBLE", "merchantRef": "…", "applicationRef": "…"}
}
```

错误：10400 资料不合格（缺证照、统编、帐号格式、`agreed=false`）；70001 主体类型与行业不符（已有，`OpsServiceImpl.java` 788 行）；10409 已有不同内容的进行中申请、或门店主体不是占位主体；`AccessDenied(FORBIDDEN)` 头与体不一致；10403 不是店主；70037 主体数已满（已有闸）。near-pay 失败**不报错**，落在 `payService.status`。

幂等：不新增幂等表。已有进行中申请时，比对 `name、legalForm、industry、contactPhone、qualificationItems、licenses` —— 全同视为重放（`replayed=true`，跳过建申请，协议已同意则原样返回时间），只补发 near-pay；near-pay 侧事件号由资料摘要决定，天然幂等。不同就 10409。

与 App 建议的差异：

| # | 差异 | 理由 |
|---|---|---|
| 1 | 响应从「无」改为上面的对象 | App 丢弃响应体，不破坏；给出 `payService` 才能让 App 直接显示「审核中 / 暂不可申请」而不是再读一次 |
| 2 | `settleAccount` 从 `{bank, account}` 改为 `{bankCode, accountName, account}` | 渠道进件要户名；`bank` 是显示名不是代码（App 现在送的是界面上选的银行名） |
| 3 | `channels` 从 `[{payChannel, channelMerchantNo?}]` 改为字符串数组，且 `channelMerchantNo` 不收 | near-pay 申请不分渠道（`capabilityType=FIAT`）；已有商户号走 N2，是另一种审核（核实已有号 vs 新开户），混在一张单里会让一次提交里一半要开户、一半要核实，失败时说不清是哪一半。v1 `channels` 只校验在本市场启用列表内并作为偏好透传，`JKOPAY` 直接 70045 |
| 4 | `entityNo`/`storeNo` 可空 | 由门店头决定，与 `/biz/merchant/apply` 一致；给了就必须一致 |

### N2 `POST /biz/merchant/payment/bind`

| 项 | 值 |
|---|---|
| 头 | 门店头必带 |
| 权限码 | `biz:finance` |
| 路由登记 | **`CommerceRoutePolicy` foundation 通道**：`case "POST /biz/merchant/payment/bind" -> "biz:finance"`；不进 `ShopStoreMemberRoutes` |

请求：`{"requestNo": "App 生成，≤64", "entityNo": "可空，须等于 X-Entity-No", "payChannel": "NEWEBPAY|LINEPAY", "channelMerchantNo": "≤64", "evidenceUrl": "可空，签约书或后台截图，/biz/upload/image 地址"}`

响应 `data`：`PaymentApplymentVO` 一行（与 `/biz/merchant/payment` 同形状）：`applyStatus=APPLYING`、`submitted=true`、`canReceiveMoney=false`，另加 `source: "BIND"`、`channelMerchantMasked: "MS1****5678"`。

错误：10400 字段缺失或带了 `credential`；70045 渠道本市场不可用；10409 该渠道已有 `ACTIVE` 或在途行、或主体还没申请支付服务；10505 支付服务不可用（内嵌部署 / near-pay 失败，**本地不留行**）。`requestNo` 相同的重发返回同一行。

near-pay 依赖：新内部端点 `POST /internal/pay/v2/source-links/channel-account-claims {sourceScope, sourceEntityRef, requestNo, payChannel, channelMerchantId, evidenceRef, actorRef}` → `{claimRef, status: PENDING_VERIFY}`，以及 `GET …/channel-account-claims/{claimRef}`。运营在 near-pay 核实时建渠道账户、录密钥、`verify`（已有端点）。

与 App 建议的差异：去掉 `credential`（待决 3）；加 `requestNo`（§3 通用约定的幂等要求）；结果是「待核实」不是 mock 里的立即 `ACTIVE`；App mock 用的拒绝码 10422 在后端是 `STAFF_SCOPE_ON_FULL_ACCESS`（`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` 244 行），**不能用**，改为 10400 / near-pay 审核拒绝后行上的 `rejectReason`。

### N1 `POST /biz/store/{storeNo}/entity`

| 项 | 值 |
|---|---|
| 头 | `X-Store-No` 必须等于路径门店；`X-Entity-No` 是**旧**主体 |
| 权限码 | `biz:store:admin`（同 `POST /biz/store/{storeNo}/payment`） |
| 路由登记 | **`ShopStoreMemberRoutes`**：`HostRoute.of("POST", STORE + "entity")`（门店级动作，与「本店开通收款」同类） |

请求：`{"entityNo": "目标主体"}`。注意：控制器**不能**把体里的 `entityNo` 交给 `FoundationTarget#check` —— 它本来就和当前主体不同。

响应 `data`：`{"storeNo": "…", "entityNo": "新", "previousEntityNo": "旧", "payReady": true, "revokedStaff": 2, "placeholderRetired": true}`

前置条件（全部满足才改，任一不满足 10409，目标不是自己的 10403，门店不在旧主体下 10404）：

1. 门店当前主体是 `PENDING_LICENSE`，且当前用户是它的在职店主
2. 目标主体 `ACTIVE`，当前用户是它的在职店主（`requireOwned`）
3. 门店没有任何订单、售后、线下收款、储值账户
4. 门店订阅只有 FREE（没有付费期、没有商城订单）
5. 占位主体在 near-pay 的申请状态是 `NONE`（门店从未在 near-pay 关联过，见 §1 第 18 行）
6. 旧主体没有进行中的入驻申请（否则审核通过时会把申请升级到一个已经没有门店的主体上）

同一事务里做：改 `mch_store.entity_no`、`is_default=false`、`pay_merchant_no=null`、`payment_changed_at`；FREE 订阅账户的 `entity_no` 跟着改（经 commerce 端口）；删除该门店非店主的 `mch_store_role`；占位主体下没有门店了则逻辑删除占位主体并停用其成员行；`OpsService#audit(…, critical=true, …)` 记 A→B；提交后 `payMirror.changed` 触发门店同步。

与 App 建议的差异：App 允许选「审核中 / 被驳回」的主体（`store_payment_page.dart` 选项过滤 `e.review != MerchantReview.none`），本文只允许 `ACTIVE`（待决 2）；App 改挂后不知道员工授权已撤销，响应带 `revokedStaff` 供提示。改挂后 App 必须重读 `/biz/stores/mine` 换掉缓存的 `X-Entity-No`，否则后续主体级请求会被 `FoundationTarget` 拒绝。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantOnboardingController.java` | N3、N2 两个端点（只有 `/biz/merchant` 一个资源前缀，不碰控制器内聚棘轮） |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/MerchantOnboardingRequests.java` | 校验与映射纯函数（主体码、证照拆分、near-pay profile） |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/MerchantOnboardingOrchestrator.java`（或放 `payclient` 包，实现时按 ArchitectureTest 定位） | 本地事务 + 远程调用编排 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayServiceApplyRequests.java` | `Form` 加 `industry, materials, settlement`（可选）；摘要不变 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PaySourceLinkApi.java` | `Profile` 加 `settlement`；新增 `claimChannelAccount`、`claimStatus` |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreEntityController.java` | N1（不加进 1152 行的 `BizMerchantController`） |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/StoreRelinkService.java` + `impl/StoreRelinkServiceImpl.java` | N1 规则与事务 |
| 新增 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/merchant/StoreRelinkGuardPort.java` | 「这家店有没有交易 / 付费订阅」只读端口；实现分别在订单域（shop-core）与 `commercebridge` |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantChannelBindService.java` + impl | N2 本地行 |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantPaymentServiceImpl.java`、`entity/MchPaymentMerchant.java` | `source` / `channelMerchantMasked`；`refresh` 对 BIND 行查 near-pay |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__payment_merchant_bind_source.sql` | `ALTER TABLE mch_payment_merchant ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'APPLY' COMMENT 'APPLY 平台进件 / BIND 绑定已有商户号', ADD COLUMN channel_merchant_masked VARCHAR(64) DEFAULT NULL COMMENT '已有商户号掩码；明文不落库'`。166 上带 DDL，交接时标出由 root 先执行 |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | foundation 通道加两条 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 加 `POST /biz/store/{id}/entity` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 登记三个端点的权限码 |
| 修改 | `known-app-backend-orphan.txt` | 三个端点 uni-app b-app 不接，按 `pay-service/apply` 先例（151 行）登记 |
| 修改 | `docs/api/openapi-b-onboarding.json`、`docs/api/B端注册到进件-标准API文档.md` | 三个端点的契约 |
| 依赖 | near-pay 仓库（另立 TDD） | `settlement` 字段、渠道账户认领端点与运营审核页 |

---

## §3 选型（2 档）

### N3 结算帐户放哪

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 透传给 near-pay `source-links/apply`，ai-shop 不落库 | 明文只在一次请求里（ADR-002 §5 同一原则）；渠道直清本来就要它 | 依赖 near-pay 加字段；near-pay 失败要商家重提一次 | ✅ 采用 |
| B 存 ai-shop `mch_payout_account` | 已有加密、掩码、审核 | 语义是平台代付卡、要求户名 = 主体名；台湾渠道直清用不上，等于造一份没人读的数据 | ❌ |
| C 先不收，审核通过后再补 | 不依赖 near-pay | App 四步表单要拆，商家多跑一趟 | ❌（作为 near-pay 赶不上时的后备，见待决 4） |

### N2 已有商户号怎么进系统

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A App 收商户号 + 密钥，经 ai-shop 转 near-pay | 一步到位 | 密钥经过 App 与 ai-shop 两跳，日志、抓包、错误回显任何一处漏了就是渠道密钥泄露；近一半商户拿不准哪个是密钥 | ❌ |
| B 只收商户号 + 证明，运营在 near-pay 核实时录密钥 | 密钥只在 near-pay 运营端出现，复用已有 `verify` | 要运营动手，开通慢一天 | ✅ 采用 |
| C App 直连 near-pay 商户端 `BizChannelAccountController` | 不经 ai-shop | 那是 near-pay 自己的商户鉴权域（`X-Merchant-No`），App 没有那套登录；且它也只能绑已有账户、不能建 | ❌ |

### N1 改挂的范围

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 通用换主体（关系定稿 §3.4 六条约束 + 平台审核） | 覆盖个体户升公司等场景 | 动纳税主体、历史订单、券与积分、near-pay 关联（只能运营换），一期做不完；与「产品明确暂不做」冲突 | ❌ 一期 |
| B 只做占位主体门店认领已有 ACTIVE 主体 | 正好覆盖 App C02 的场景（`unlinked` 才出现）；占位主体从未认证、没有交易，不涉及纳税主体变化，可免审核 | 门店一旦有交易就不能再认领，只能新建资料 | ✅ 采用 |
| C 不做 N1，App C02 只留「以本店新建资料」 | 零后端 | 已有一张执照的老板开第二家店，要再交一遍同一张执照、再审一次 | 后备 |

---

## §4 风险（2 档）

| 风险 | 影响 | 缓解 |
|---|---|---|
| N3 本地成功、near-pay 失败 | 商家以为提交完了，平台商户没建 | 响应 `payService.status=UNAVAILABLE`，App 显示「收款申请未送达，点重试」；重发走重放，只补 near-pay |
| `PayServiceApplyRequests` 摘要被改动 | 已申请主体重发被 near-pay 当成新修订，进入重复待审 | 摘要只算原五个字段；测试固定摘要 |
| 结算帐号进日志 | 一批商户银行帐号泄露 | 输入 record 重写 `toString`；请求日志对 `/biz/merchant/onboarding` 体脱敏；测试断言日志不含明文 |
| N1 改挂后旧 `X-Entity-No` 仍在 App 缓存 | 后续主体级请求被拒，像「功能坏了」 | 响应带新 `entityNo`；App 必须重读门店列表（交接给 App） |
| N1 漏掉某种「门店有交易」的数据源（如储值、团购） | 交易的结算方被改到新主体，税务问题 | 守卫端口按白名单列数据源，实现时逐表核；拿不准的一律算「有交易」 |
| N1 商城订阅账户的 `entity_no` 是 commerce 模块的数据 | 跨模块写 | 只允许 FREE；经 commerce 端口改，不在 shop-merchant 里直写 commerce 表 |
| N2 near-pay 端点没有按时提供 | App 正式包仍拦着 N2 | N2 单独冻结，不阻塞 N3、N1 |
| 两套进件并存（旧 `apply + payment` 与 N3） | 同一主体两份资料 | N3 内部就是旧 `apply`，同一张申请表、同一唯一键，不会出现两份；旧 `/biz/merchant/payment` 在台湾本来 70045 |

---

## §5 对账三 · 实现 → 需求（测试，计划，未跑）

| AC | 测试方法（计划） | 跑过 | 验证点 |
|---|---|---|---|
| A1 | `MerchantOnboardingRequestsTest#mapsSubjectsToCanonicalLegalForms`、`#splitsRegistrationIntoBusinessLicenseItem`；`MerchantOnboardingFlowTest#submitCreatesPendingApplyOnPlaceholderEntity` | 未跑 | `SOLE` 落 `INDIVIDUAL`；申请 `entity_no` = 占位主体 |
| A2 | `MerchantOnboardingFlowTest#agreedRecordsAgreementInSameTransaction`、`#notAgreedWritesNothing` | 未跑 | |
| A3 | `MerchantOnboardingRequestsTest#rejectsMissingLicenseBySubject`、`#rejectsTaxNoNotEightDigits`、`#rejectsAccountOutsideTenToSixteenDigits` | 未跑 | |
| A4 | `MerchantOnboardingFlowTest#forwardsProfileMaterialsAndSettlementToNearPay`（起本地 HTTP 服务，同 `PayServiceApplyRequestsTest#sendsThroughPayClientWithCallerHeaders` 做法） | 未跑 | |
| A5 | `MerchantOnboardingFlowTest#settleAccountNeverPersistedOrLogged`；`PayServiceApplyRequestsTest#digestIgnoresSettlementAndMaterials` | 未跑 | 查库、抓日志、事件号不变 |
| A6 | `MerchantOnboardingFlowTest#nearPayDownStillCommitsLocalAndReportsUnavailable`、`#replayResendsOnlyToNearPay` | 未跑 | |
| A7 | `MerchantOnboardingFlowTest#differentDataWhilePendingIsConflict` | 未跑 | 10409 |
| A8 | `MerchantOnboardingFlowTest#verifiedEntityIsConflict` | 未跑 | |
| A9、B7 | `StoreScopedDailyRoutesFlowTest#merchantOnboardingPassesAdmission`、`#channelBindPassesAdmission`；`BizEndpointPermTest`（登记） | 未跑 | 去掉 foundation 登记后变红 |
| A10 | `MerchantOnboardingFlowTest#bodyEntityMismatchIsDenied` | 未跑 | |
| B1 | `MerchantChannelBindServiceTest#bindCreatesApplyingRowWithMaskOnly` | 未跑 | 库里无明文 |
| B2 | `MerchantChannelBindServiceTest#disabledChannelIsUnavailable` | 未跑 | 70045 |
| B3、B4 | `MerchantChannelBindServiceTest#activeOrInFlightChannelIsConflict`、`#withoutPayServiceIsConflict` | 未跑 | |
| B5 | `MerchantPaymentServiceImplTest#refreshBindRowReadsNearPayClaim` | 未跑 | |
| B6 | `BizMerchantOnboardingControllerTest#bindRejectsCredentialField` | 未跑 | 10400 |
| C1 | `StoreRelinkServiceImplTest#relinksPlaceholderStoreToOwnedActiveEntity` | 未跑 | `entity_no`、`is_default`、`pay_merchant_no`、审计 |
| C2 | `StoreRelinkServiceImplTest#foreignTargetIsForbidden`、`#inactiveTargetIsConflict` | 未跑 | |
| C3 | `StoreRelinkServiceImplTest#nonPlaceholderSourceIsConflict` | 未跑 | |
| C4 | `StoreRelinkServiceImplTest#storeWithOrdersIsConflict`、`#paidSubscriptionIsConflict`、`#placeholderAppliedToNearPayIsConflict`、`#pendingApplyOnPlaceholderIsConflict` | 未跑 | |
| C5 | `StoreRelinkServiceImplTest#revokesStaffRolesAndRetiresEmptyPlaceholder` | 未跑 | |
| C6 | `StoreRelinkServiceImplTest#triggersStoreSync` | 未跑 | |
| C7 | `StoreRelinkServiceImplTest#alreadyOnTargetIsIdempotent` | 未跑 | |
| C8 | `StoreScopedDailyRoutesFlowTest#storeRelinkPassesAdmission`；`BizEndpointPermTest` | 未跑 | |

闸门（实现后要跑）：`ArchitectureTest`、`BizEndpointPermTest`、`StoreScopedDailyRoutesFlowTest`、commerce starter 测试、`check-app-backend-orphan.py`、`SchemaDriftTest`（迁移）、`BackendI18nParityTest`（确认无新码）。

---

## 待决（需产品 / 用户拍板）

| # | 问题 | 建议 |
|---|---|---|
| 1 | **门店改挂主体做不做？** 文档互相矛盾：`账号主体门店-关系定稿` §3 定「可切换」，`门店注册到进件-流程节点与代码对齐` 158 行写「产品明确暂不做」，`多门店与分享激励-需求` 四节列为「不做」 | 一期只做「占位主体门店认领本账号已有主体」（§3 N1 方案 B），通用换主体继续不做。需要一份 ADR 记下这个边界 |
| 2 | N1 能不能选「审核中 / 被驳回」的主体？App 现在允许 | 只允许 `ACTIVE`。审核中的主体可能被驳回，门店就挂在一张不会生效的执照下；App 列表里把非 ACTIVE 的置灰并写「审核通过后可选」 |
| 3 | N2 要不要在 App 收渠道密钥（`credential`）？ | 不收。只收商户号 + 可选证明图，运营在 near-pay 核实时录密钥（§3 N2 方案 B）。需要一份 ADR |
| 4 | 结算帐户：near-pay 能否在 `source-links/apply` 加 `settlement`？赶不上怎么办 | 能则透传（§3 方案 A）；赶不上则 N3 先不收 `settleAccount`，App 第四步改为「审核通过后补填」，不要临时存到 ai-shop |
| 5 | `PERSONAL`（无统编自然人）在台湾能不能开收款？`pay-service/apply` 要求统编，没有就 10400 | 一期只建入驻申请、不发 near-pay（`payService.status=NOT_ELIGIBLE`），App 提示「个人身份暂不支持线上收款」；与 `TDD-入驻分阶段与进件简化` §5.2「无照个人自营模式下不开放」口径一致，请产品确认台湾是否同样 |
| 6 | `channels` 选择有没有意义？near-pay 按市场开渠道，不按申请勾选 | v1 只校验、透传为偏好，不承诺「只开勾选的」；`JKOPAY` 后端无代码，选了直接 70045。若产品要「只开勾选的」，需 near-pay 支持 |
| 7 | 要不要新端点？还是 App 串已有接口 | 要新端点（§1「组合」小节末的三条理由）。若后端排期不够，后备是 App 按 §1 组合表串 `apply → agreement/accept → pay-service/apply`，结算帐户按待决 4 后补 |
| 8 | 身份证正反面是否进 `mch_qualification`？ | 不进。它不是经营资质，进了会被上架闸门、过期扫描当成资质处理；只放申请单 `licenses` 与 near-pay `materials` |
| 9 | 占位主体被认领后怎么处理？ | 逻辑删除（`deleted=1`，`ShopCommerceStoreAdapter` 的 SQL 已按 `m.deleted=0` 过滤），成员行停用；不新增主体状态值（新状态要改 B 端状态映射 `bizStatus` 与 App 枚举） |
| 10 | N1 撤销员工授权要不要先确认？ | 要。App 在确认页写明「本店 n 名员工的授权会失效，需在新商户下重新授权」，后端只执行不询问 |
| 11 | 旧进件 `POST /biz/merchant/payment` 在台湾怎么办？它只有 stub 网关，正式环境 70045 | App 正式包台湾市场不再走它，渠道状态以 `GET /biz/merchant/pay-service` 为准；`/biz/merchant/payment` 行状态与 near-pay 真实状态的同步另立题目 |
