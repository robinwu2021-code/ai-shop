> 发布更新（2026-11-10）：本批代码已提交并发布 ai-shop API / 独立 near-pay 至166；库存保留已部署版本，OPS未重启、App未发包。N1新建仍关闭且受库存准入限制；精确范围、迁移、验证与回退见 [发布记录](../handoff/nearpay-biz-onboarding-2026-11-09/release-166-2026-11-10/README.md)，`git show --stat` 见同目录commit-stats。

# TDD-商户App商户资料提交

状态：N3平台资料提交/N4审核回显已本地实现（2026-11-10）；N1写入/恢复与N2独立PAY认领关联已完成本地真实HTTP联调。N3旧渠道透传方案由文末2026-11-10用户确认的独立near-pay拆分替代；旧§5未实现条目不代表本批结论。
ADR：[ADR-055](./ADR/ADR-055-门店改挂主体限于占位认领.md) · [ADR-056](./ADR/ADR-056-商户自带渠道商户号的录入与核实路径.md)
档位：**2**。N3（一次提交资料）、N4（主体读接口补审核字段）、N1 预检单看都是 1 档（新端点或响应加字段、复用已有权限码、ai-shop 不加表）；
N1（门店改挂主体）改门店与主体的关系、牵动 near-pay 门店投影与商城订阅，N2（绑定已有渠道商户号）决定商户渠道凭据从哪条路进系统 —— 两者都不可逆，按 2 档写 §3 §4。**需要两份 ADR（本文不写）**：「门店改挂主体（占位认领）」「商户自带渠道商户号的录入路径」。
关联需求：
- 商户 App 交接文档 （ai-shop-bapp 仓库 `docs/implementation/功能矩阵与API交接文档.md`（main `31a56c1`）） §3 通用约定、第 4 模块「商户与进件」（4-05 / 4-06 / 4-07）、附录 B 第 4 行
- 商户 App 对本文的评审：ai-shop-bapp `docs/implementation/TDD评审-商户App.md`「最需要先定的」第 4 行、「第二组 · 二、TDD-商户App商户资料提交」（R1–R10、M1–M9）
- [入驻进件建店-需求与四层对齐](../requirements/入驻进件建店-需求与四层对齐.md)（入驻 / 进件 / 建店三段独立；4.2.6 门店切换主体）
- [账号主体门店-关系定稿](./design/账号主体门店-关系定稿.md) §3（门店可切换主体、六条硬约束）
- [TDD-运营端申请支付服务](./TDD-运营端申请支付服务.md) §7（B 端 `pay-service` 申请与 foundation 通道）
- [TDD-入驻分阶段与进件简化](./TDD-入驻分阶段与进件简化.md) §5.2（无照个人能否开店）

依据代码：`origin/codex/ai-shop-codex` 之上的 `codex/bapp-handoff-tdd`（`0f1e733f8`）；near-pay 依据本机 `near-pay` 仓库（`993cf6b49`）。App 依据本机 `ai-shop-bapp`：起草时 `d10b557`，评审基准 `58a3995`，本次核对 `a210d75` 的 `apps/merchant_app/lib/features/merchant/merchant_api.dart`、`merchant_pages.dart`、`store_payment_page.dart`、`dev/mock/mock_v5.dart`。
创建：2026-11-08 · 最后更新：2026-11-08

---

## 一句话（L1）

商户 App 的「商户资料」接口，后端的最终建议是：**N3 一次提交 = 一个按门店主体状态分流的编排端点**（占位主体：建或改入驻申请 + 同意协议 + 申请支付服务；已核准主体：只把收款资料补件送 near-pay），结算账户只透传给 near-pay、ai-shop 不落库；**N4 主体读接口补上审核态**，让进度页与改挂选择列表有数据；**N2 绑定已有商户号 = 提交一张待运营核实的绑定申请**，不收密钥；**N1 门店改挂主体 = 只做「占位主体门店认领本账号已有主体」**，带一个只读预检说清楚为什么不能改。

## 结构（L2）

| 接口（App 建议） | 本文建议 | 能否用已有接口组合 | 档 | ai-shop 新增 | 依赖 near-pay |
|---|---|---|---|---|---|
| N3 `POST /biz/merchant/onboarding` | 保留路径；必带门店头；按门店主体状态分流（新建 / 重放 / 修改 / 只补收款资料） | **大半能**：资料、证照、协议、申请支付服务都有现成接口；结算账户、改进行中申请的证照、补件重提没有去处 | 1 | 新控制器 + 编排服务 + `OpsService` 一个改申请的方法；无表 | `source-links/apply` 的 `profile` 加 `settlement`（透传）；`materials[].ref` 的 `file:` 引用如何解析 |
| N4 `GET /biz/entities`、`GET /biz/entity/{entityNo}` | 响应加 `review`、`applyNo`、`rejectReason`、`taxNo`、`owner` | 不能：`EntityVO` 没有这些字段 | 1 | 响应字段；无表 | 无 |
| N2 `POST /biz/merchant/payment/bind` | 保留路径；去掉 `credential`，加 `requestNo`；结果是「待核实」不是「已开通」；只能在申请过支付服务之后调 | 不能：ai-shop 没有；near-pay 只有运营端建渠道账户 | 2 | 端点 + `mch_payment_merchant` 加两列（V6xx 待定） | 新内部端点「渠道账户认领」 |
| N1 `POST /biz/store/{storeNo}/entity` + 预检 `GET …/entity/relink-check` | 保留路径；只允许「占位主体门店 → 本账号 ACTIVE 主体」；预检回不能改的机器码 | 不能：`/biz/store/{storeNo}/payment` 只能在本主体内换收款号 | 2 | 两个端点 + 改挂服务 + 两个只读守卫端口；无表 | 无（依靠门店同步把投影映射到新主体） |

N2、N3 都是**主体级**动作，要主体级收款目标（`FoundationTarget`），必须走 `CommerceRoutePolicy` 的 foundation 通道，**不能**登记在 `ShopStoreMemberRoutes` —— 11/07 在 166 上就是因为把 `pay-service` 登记错了地方，新模型门店一直 70006（见 §1 第 10 行）。N1、N4 是门店级 / 店主级读写，与 `POST /biz/store/{storeNo}/payment`、`/biz/entities` 同类，登记在 `ShopStoreMemberRoutes`。

## 按商户 App 评审的修订（2026-11-08）

评审条目编号沿用评审文档。「属实」= 在后端代码里核对过，出处见所指的 §1 行。

| 评审条目 | 核对结果 | 处理 | 改在哪 |
|---|---|---|---|
| 跨草案 4 / R1：无门店新建商户资料（M04）回 10409，或被静默挂到 `merchantScope()` 随手拿到的那家占位店 | 属实且更具体：不带门店头时，新模型（`commerce.store-creation.enabled=true`，166 与本机已开）直接 70198（§1 第 21 行）；带了一家已认证主体的门店头，底层抛 10403（§1 第 1 行）；带了占位店则挂到那家店 | 契约改为 **N3 必带门店头，缺则 70198**，`entityNo/storeNo` 一律以门店头为准；服务端无从判断 App 选的那家店是不是用户本意，所以「M04 留不留、怎么留」是产品取舍 | §0 A1、A8；§2 N3「分流」；已拍板 1 |
| 跨草案 4 / R2：改已核准资料、J02 补件都 10409 | 属实：已核准主体没有任何改资料入口（§1 第 22 行）；而 near-pay 补件（`NEED_MORE`）重提又会撞同修订号冲突（§1 第 25 行） | N3 对 **ACTIVE 主体** 改为「只补收款资料」：主体类型、名称、统编须与已核准一致，其余（负责人、电话、地址、证照、结算账户）送 near-pay 进新修订；J02 = near-pay `NEED_MORE`/`REJECTED` 后用 N3 重提。改已核准的名称 / 主体类型仍 10409，是否开放见已拍板 2 | §0 A8、A11；§2 N3「分流」「near-pay 修订号」 |
| 跨草案 4 / R3 / M3：提交后主体仍 `PENDING_LICENSE`、不建渠道行，C08 出不来 | 属实：N3 不写 `mch_payment_merchant`；`EntityVO.status` 只有主体生命周期，没有审核态（§1 第 13 行） | 新增 **N4**：主体读接口加 `review` 等字段（App 读 `review`，不再拿 `status` 推）；C08 渠道一格读已有的 `GET /biz/merchant/pay-service`；明确 N3 **不会**出现 `source=APPLY` 的 APPLYING 行 | §0 A12、D1–D4；§2 N4 |
| R4：`EntityVO` 缺字段不能拖到另一份 TDD | 属实：C02 列表靠 `review` 过滤，没字段就全是 `none` | 采纳：并入本文 N4，与 N1 同批交付；《TDD-商户App接口字段对齐》不再接这一条 | §0 孤立项；§2 N4 |
| 「App 在 N3 之前就调 bind 会被 10409」（N2 入口在表单里） | 属实：B4 要求支付服务状态不是 `NONE` | 契约不放宽（未申请支付服务就核实已有商户号，near-pay 没有可挂的平台商户）；写明 N2 的前置与 App 入口位置；`evidenceUrl` 改 `evidenceAssetKey`（near-pay 只收 `file:` 引用） | §0 B4、B8；§2 N2 |
| R8：重放判据太严，改一个错字就卡死 | 属实：进行中申请只能新建或 10409；已有的 `updateApply` 只给 C 端、只认 `PENDING`、**不改证照**（§1 第 22 行） | N3 对**本门店主体**的 `PENDING` 申请改为「修改」：新方法改申请（含证照）并刷新主体修订号再送 near-pay；`REVIEWING` 回已有的 10467；不做撤回 | §0 A7；§2 N3 |
| R9：N1 的 10409 没有原因 | 属实，但「`data.reason`」**不照改**：本仓库业务异常统一回 `ApiResult<Void>`，`BizException` 不带数据（§1 第 27 行），为一个端点改全局错误包不划算 | 改为**只读预检** `GET /biz/store/{storeNo}/entity/relink-check`，200 回 `blockers[]` 机器码与将失效员工数；同时回答 M5 | §0 C9；§2 N1 |
| R10：v5 表单不知道行业与主体的可选集 | 属实，但后端**已有**：`GET /common/master-data` 一次回行业（`microAllowed`）与主体（`industryGated`、`needLicense`），就是为这一屏做的（§1 第 23 行） | 不加端点；契约写明 App 读它置灰，判据与 `requireSubjectAllowedByIndustry` 同一份 | §2 N3「字段」 |
| 「App 需要改的」：`profile.industry` 改送 `store.industryCode / subIndustryCode`（M1） | **与后端不符，不照改**：入驻申请的 `industry` 是 `sys_industry.industry`（餐饮 / 零售等七类，`MchEntityApply.industry` 注释），门店的 `industryCode` 是经营类型目录，必须等于 `businessTypeCode`（`BizMerchantController#requireIndustry`）——两套码。送门店码会让小微主体查不到行业行而 70001，其余主体落一个不在 `sys_industry` 里的值 | 契约改为送 `/common/master-data` 的 `industries[].industry`；`OTHER` 时带 `industryNote`（≤64，V360 列宽） | §2 N3「字段」 |
| 草案自查（评审未提，修订 N3/J02 时核出） | near-pay `materials[].ref` 只收 `file:`/`cipher:` 引用、`kind` 只收四种（§1 第 24 行），草案写的「`ref` = 图片 URL」会被 near-pay 400 | 证照改送上传返回的 `assetKey`，服务端映射为 `file:<assetKey>`；`kind` 映射到 near-pay 值域 | §0 A3、A4；§2 N3 |
| M2 结算户名与银行清单 | 后端两侧都没有银行清单接口（ai-shop、near-pay 均无 `bank` 相关商户端点）；户名规则归 near-pay 渠道进件 | 产品取舍 | 已拍板 4 |
| M4 地址是否必填 | 后端不要求：入驻申请没有地址列，near-pay `registeredAddress` 可空（`PayServiceApplyRequests.java` 59 行 `trimOrNull`） | 契约：可空 | §2 N3「字段」 |
| M5 将失效人数 | 见 R9 | 预检回 `revokedStaff` | §2 N1 |
| M6 `evidenceUrl` 用哪个 `bizType` | `QUAL`：落 `_ENTITY` 档、私有路径；上传本身不进 `mch_qualification`（只有 `/biz/qualifications/save` 才写） | 契约写明；字段改 `evidenceAssetKey` | §2 N2 |
| M7 是否收 `Idempotency-Key` | N3 按内容判重放，不读该头 | 契约写明：App 不必发；发了忽略 | §2 N3「幂等」 |
| M8 驳回后重提是新单还是改原单 | 新单（`MchEntityApply` 43 行：REJECTED 是终态，重提新开一张），`applyNo` 会变 | 契约写明；App 进度按主体 `review` 显示，不按 `applyNo` | §2 N3、N4 |
| M9 改挂响应能否直接带新分组 | 可以：`/biz/stores/mine` 的组装（`MerchantEntityService#myStores`）可在同一请求末尾调用 | 响应加 `stores`（与 `/biz/stores/mine` 同形） | §2 N1 |
| R5 / R6 / R7（原型与文案要改、需要产品签字） | 产品取舍 | 并入已拍板 5、6、7，写明原型要同步改的屏 | 已拍板 |

---

## §0 对账一 · 需求 → 设计

需求来自交接文档 4-05 / 4-06 / 4-07、App 的 mock 行为（`mock_v5.dart` 486–537 行）与商户 App 评审。App 现在只认 `code=0`、响应体丢弃（`merchant_api.dart` 三处 `(_) => true`）；评审已确认 App 会改为解析本文的响应对象。

### N3 一次提交商户资料（v5 C04–C07；J02 补件；M04 见已拍板 1）

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| A1 | Given 店主带门店头选中一家挂在自己 `PENDING_LICENSE` 占位主体下的门店，且没有进行中的申请 When `POST /biz/merchant/onboarding` 资料齐全 Then 新建一张 `PENDING` 入驻申请，`entity_no` = 该占位主体；主体类型按 `COMPANY→ENTERPRISE`、`SOLE→INDIVIDUAL`、`PERSONAL→MICRO` 落权威码。Given 不带门店头 Then 70198（与 `/biz/merchant/apply` 新模型下同一行为），什么都不写 | `MerchantOnboardingService#submit` → `OpsService#createMerchantApply(cmd, entityNo)`；映射在 `MerchantOnboardingRequests#subject` |
| A2 | Given `agreed=true` When 新建或修改 Then 该申请的 `agreed_at` 有值，响应带 `agreedAt`；Given `agreed=false` Then 10400，**什么都不写** | 编排先校验再写；`OpsService#acceptAgreement(userNo, entityNo)` 与写申请同一事务 |
| A3 | Given 主体类型 When 缺该类型的必传证照、证照 `assetKey` 不属于门店主体的 `_ENTITY/qual/` 目录、`COMPANY`/`SOLE` 统编不是 8 位数字、帐号不是 10–16 位数字、`ownerPhone` 不是 E.164、`industry` 不在 `/common/master-data` 的行业里、`industry=OTHER` 而 `industryNote` 为空 Then 10400，不写库、不调 near-pay | `MerchantOnboardingRequests#validate`（纯函数） |
| A4 | Given 台湾市场且独立部署了支付服务 When 本地写入成功 Then 用同一份资料调 near-pay `source-links/apply`（法定名称 = `name`、统编 = `taxNo`、负责人 = `owner`、电话、地址、行业、证照作 `materials[{kind, ref: "file:"+assetKey}]`、结算账户作 `settlement`），响应带 `payService{status, merchantRef, applicationRef}` | 复用 `PayServiceApplyRequests.build` + `PaySourceLinkApi#apply`；`Form` 加可选字段 |
| A5 | Given 任何时候 When 提交 Then 结算帐号明文**不落 ai-shop 库、不进日志、不进事件号摘要**，响应只回掩码 | `SettleAccountInput#toString` 掩码；`PayServiceApplyRequests.digest` 不含新增字段 |
| A6 | Given near-pay 不可用（内嵌部署或调用失败） When 提交 Then 本地申请与协议照常提交，HTTP 200，`payService.status=UNAVAILABLE`；When 原样重发 Then 不新建第二张申请，只补发 near-pay | 编排先本地事务后远程调用；重放判据见 §2 N3「幂等」 |
| A7 | Given 本门店主体已有一张 `PENDING` 申请 When 内容相同 Then 重放（A6，`replayed=true`）；When 内容不同 Then **修改这张申请**（含证照），`applyNo` 不变，`revised=true`，并按新资料重送 near-pay。Given 申请已 `REVIEWING` Then 10467（已有码 `APPLY_NOT_EDITABLE`）。Given 本人在**另一个**主体上有进行中申请 Then 10409 | `OpsService#reviseMerchantApply`（新）；`OpsServiceImpl` 已有的「一人一份进行中」与 `uk_apply_active_owner` 不变 |
| A8 | Given 门店主体已核准（`ACTIVE`） When 提交 Then **不碰入驻申请**，只把收款资料送 near-pay（J02 补件、已认证主体首次申请收款都走这里），`applyStatus=APPROVED`；Given 请求的主体类型、名称或统编与已核准的不一致 Then 10409（改已核准资料见已拍板 2）；Given near-pay 状态是 `PENDING` 或 `APPROVED` Then 10409（审核中不叠修订；已通过后改资料同属已拍板 2）；Given 主体 `SUSPENDED`/`BANNED` Then 10409 | 编排层按门店主体状态分流（§2 N3「分流」） |
| A9 | Given 新模型（STORE_V1）门店的店主 When 调 N3 Then 通过门店准入（不是 70006），权限码 `biz:finance` | `CommerceRoutePolicy` foundation 通道加 `POST /biz/merchant/onboarding` |
| A10 | Given 请求体 `entityNo`/`storeNo` 与 `X-Entity-No`/`X-Store-No` 不一致 When 提交 Then 拒绝（`AccessDenied(FORBIDDEN)`，经 `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/CommerceAccessErrors.java` 回包，与 `pay-service/apply` 同一行为） | `FoundationPaymentAccess#require` + `FoundationTarget#check` |
| A11 | Given 资料变了（修改路径、驳回后新建、A8 补件、只改了结算账户） When 送 near-pay Then near-pay 收到更高的主体修订号、进新的资料修订，**不会**因同修订号内容不同而冲突；Given 原样重放 Then 修订号不变、near-pay 幂等 | 本地写入时刷新主体修订号；near-pay 回 409 时刷新后重发一次（§2 N3「near-pay 修订号」） |
| A12 | Given N3 成功 Then 不写 `mch_payment_merchant`（不出现 `source=APPLY` 的 APPLYING 行）；C08 的「商户审核」读 N4 的 `review`，「渠道开通」读 `GET /biz/merchant/pay-service` | 编排服务不依赖 `MerchantPaymentService` |

### N4 主体读接口补审核字段（v5 C02、C08、M01、M02）

| AC | 需求 | 落点 |
|---|---|---|
| D1 | Given 店主 When `GET /biz/entities` 或 `GET /biz/entity/{entityNo}` Then 每个主体多回 `review`：主体非 `PENDING_LICENSE` → `APPROVED`；`PENDING_LICENSE` 时看本主体最近一张申请：`PENDING`/`REVIEWING` → `REVIEWING`，`REJECTED` → `REJECTED`，没有 → `NONE` | 组装在 shop-app（shop-merchant 读不到 `mch_entity_apply`，两模块互不依赖） |
| D2 | Given `review=REJECTED` Then 带 `rejectReason`；任何时候带最近一张申请的 `applyNo`（可空） | 同上 |
| D3 | Given 主体已核准 Then `taxNo` 取 `mch_qualification` 的 `BUSINESS_LICENSE` 证号；未核准取最近申请 `qualification_items` 里同一项的 `code`；`owner` 取最近申请的 `contact_name` | 同上 |
| D4 | Given 旧客户端 Then `status`、`legalForm` 等原字段语义不变（新增字段只加不改） | `EntityVO` 只追加字段 |

### N2 绑定已有的渠道商户号（v5 C09）

| AC | 需求 | 落点 |
|---|---|---|
| B1 | Given 主体已向 near-pay 申请过支付服务（`GET /biz/merchant/pay-service` 不是 `NONE`） When `POST /biz/merchant/payment/bind {payChannel, channelMerchantNo, requestNo}` Then near-pay 记一张「待核实」的渠道账户认领；ai-shop 该渠道行变 `APPLYING`、`source=BIND`，只存商户号掩码 | `MerchantChannelBindService#bind` → `PaySourceLinkApi#claimChannelAccount`（near-pay 新端点）；`mch_payment_merchant` 新列 |
| B2 | Given 渠道不在本市场启用列表（或是 `JKOPAY`） When 绑定 Then 70045 | `PayChannelMasterPort#enabledChannels` |
| B3 | Given 该主体该渠道已有 `ACTIVE` 行或在途申请 When 绑定 Then 10409 | 绑定服务先查 `mch_payment_merchant` |
| B4 | Given 主体还没申请支付服务（状态 `NONE`，含 N3 返回 `UNAVAILABLE`/`NOT_ELIGIBLE` 的情况） When 绑定 Then 10409。**App 不能在 N3 表单提交之前调 N2**：入口放在 N3 成功之后或 M02 / C01 的渠道行上 | 同上，查 `PaySourceLinkApi#status` |
| B5 | Given 运营在 near-pay 核实通过（已有 `POST /pay-ops/v2/channel-accounts/{payMerchantNo}/verify`） When 店主刷新 `POST /biz/merchant/payment/{payChannel}/refresh` Then 该行 `ACTIVE`，带 `payMerchantNo` | `MerchantPaymentServiceImpl#refresh` 对 `source=BIND` 行改查 near-pay 认领状态 |
| B6 | Given 请求带 `credential` When 绑定 Then 10400（v1 不经 App 收密钥，见已拍板 6） | 请求 record **显式保留** `credential` 字段、非空即 10400 —— 不能指望 Jackson 拒未知字段（本仓库只在个别控制器手动开了 `FAIL_ON_UNKNOWN_PROPERTIES`，默认是静默丢弃，密钥会被悄悄吞掉而 App 以为绑上了） |
| B7 | Given 新模型门店 When 调 N2 Then 不是 70006 | `CommerceRoutePolicy` foundation 通道 |
| B8 | Given 带 `evidenceAssetKey` When 绑定 Then 它必须属于本主体 `_ENTITY/qual/` 目录，否则 10400；送 near-pay 时是 `file:<assetKey>` | `MerchantChannelBindService#bind` |

### N1 门店改挂到另一个主体（v5 C02）

App 只在「本店还没有已审核的商户资料」时打开 C02（`store_payment_page.dart` 18–25 行 `unlinked`：本店主体 `review == none` 且没有能收款的渠道），这正是快速开店建出来的 `PENDING_LICENSE` 占位主体。本文据此把 N1 收窄为「认领」。

| AC | 需求 | 落点 |
|---|---|---|
| C1 | Given 门店挂在本账号的 `PENDING_LICENSE` 占位主体下，目标主体是本账号当店主的 `ACTIVE` 主体 When `POST /biz/store/{storeNo}/entity {entityNo}` Then `mch_store.entity_no` 改为目标，`is_default=false`，`pay_merchant_no` 清空（回到目标主体默认号），写一条 critical 审计（谁、何时、A→B） | `StoreRelinkServiceImpl#relink` |
| C2 | Given 目标主体不是本账号店主的 When 改挂 Then 10403；目标不是 `ACTIVE` Then 10409 | `MerchantEntityService#requireOwned` + 状态判断 |
| C3 | Given 门店当前主体不是占位主体（已认证主体之间互换） When 改挂 Then 10409 —— v1 不做通用换主体 | 同上 |
| C4 | Given 门店有任何订单、线下收款、储值或已付费的订阅期，或占位主体已向 near-pay 申请过支付服务，或占位主体有进行中的入驻申请 When 改挂 Then 10409 | `StoreRelinkGuardPort`（订单域、商城订阅各一个实现）+ `PaySourceLinkApi#status` |
| C5 | Given 改挂成功 Then 该门店的员工授权（`mch_store_role`，店主除外）全部撤销，响应带 `revokedStaff` 数；占位主体下已无门店时逻辑删除占位主体 | 同一事务 |
| C6 | Given 改挂成功 Then 触发门店同步，near-pay 的门店投影按新主体映射 | `payMirror.changed` / 门店同步（与 `setPayment` 同一调用） |
| C7 | Given 已经挂在目标主体 When 重发 Then 200，结果同上一次（幂等） | `relink` 开头短路 |
| C8 | Given 新模型门店 When 调 N1 或预检 Then 不是 70006，权限码 `biz:store:admin` | `ShopStoreMemberRoutes` 加两条 |
| C9 | Given 任何状态 When `GET /biz/store/{storeNo}/entity/relink-check?entityNo=` Then 200 回 `{allowed, blockers[], revokedStaff}`，`blockers` 与 C2–C4 的拒绝一一对应；**只读，不写任何东西** | `StoreRelinkServiceImpl#check`，`relink` 先调同一个 `check` |
| C10 | Given 改挂成功 Then 响应带 `stores`（与 `/biz/stores/mine` 同形），App 不必再读一次 | `MerchantEntityService#myStores` 在事务提交后调用 |

**孤立项**
- 没落点的 AC：
  - 签约 `signRequired` / `signUrl`：near-pay 进件单有 `signUrl`（near-pay `backend/pay/pay-portal/src/main/java/ai/neargo/shop/pay/api/ops/v2/OpsOnboardingDtos.java` 20–23 行），ai-shop 没有转出来。不在本文范围。
  - 原型 M04「新建商戶資料（不預填門店資訊）」：本文 N3 必带门店头，M04 怎么留见已拍板 1。
- 挂不上 AC 的设计：
  - App 的 N3 `channels[].channelMerchantNo`（「新建资料时顺便绑已有商户号」）：本文不在 N3 里收，改为 N3 成功后逐个调 N2。理由见 §2 N3「与 App 的差异」。
- 起草时的孤立项「`EntityVO` 缺 `taxNo/owner/rejectReason`，建议并入《TDD-商户App接口字段对齐》」已按评审 R4 并入本文 N4。

---

## §1 现状与影响面

### 已有能力（每行都有出处；路径相对仓库根）

| # | 能力 | 出处 | 与本文的关系 |
|---|---|---|---|
| 1 | 提交入驻申请（自填），申请挂到所选门店的占位主体；所选主体不是占位主体或不是本人在职店主时 10403 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java` 310 行 `POST /biz/merchant/apply` → `backend/shop-core/src/main/java/ai/neargo/shop/platform/impl/OpsServiceImpl.java` 524 行 `doCreateApply`；占位认领 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java` 1512 行 `merchantApplicationEntity`、1543 行 `requirePendingLicenseOwner` | N3 新建路径直接复用 |
| 2 | 一人一份进行中申请（先查 10409，唯一键 `uk_apply_active_owner` 兜底）；驳回是终态，重提新开一张 | `OpsServiceImpl.java` 531–536 行；`backend/shop-core/src/main/java/ai/neargo/shop/platform/entity/MchEntityApply.java` 43–53 行 | N3 A7；M8 的答案（`applyNo` 会变） |
| 3 | 有执照的主体类型必须带 `BUSINESS_LICENSE` 结构化资质 | `OpsServiceImpl.java` 719 行 `requireLicenseIfNeeded`（只认 `type=BUSINESS_LICENSE`） | N3 证照映射必须产出这一项 |
| 4 | 主体类型权威码与旧码 | `backend/shop-app/src/main/resources/db/migration/V2__seed_master_data.sql` 14–17 行：`MICRO`(旧 `PERSONAL`)、`INDIVIDUAL`(旧 `INDIVIDUAL_BIZ`)、`ENTERPRISE`(旧 `COMPANY`)；`backend/shop-core/src/main/java/ai/neargo/shop/platform/impl/MasterDataServiceImpl.java` 113 行 `canonicalSubject` 按码或旧码翻译 | App 的 `COMPANY`、`PERSONAL` 能被翻译，**`SOLE` 不能**（会原样落库成脏值） |
| 5 | 同意商户协议（作用于最近一张申请，只能本人，幂等） | `BizMerchantController.java` 302 行 → `OpsServiceImpl.java` 645 行 `acceptAgreement(userNo, selectedEntityNo)` | N3 的 `agreed` |
| 6 | 申请支付服务（near-pay 建平台商户 + 来源链接 + 门店关联），同资料重复提交幂等；不要求主体已核准 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPayServiceController.java` 63 行 `POST /biz/merchant/pay-service/apply`（只 `requireOwned` + `FoundationPaymentAccess`，不看主体状态）；组装 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayServiceApplyRequests.java`（61 行事件号 = 主体 + 修订号 + 资料摘要前 8 位）；客户端 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PaySourceLinkApi.java` | N3 收款部分直接复用；`Profile` 已有 `industry`、`materials` 两个字段，ai-shop 目前传 `null` / 空表 |
| 7 | 申请状态 `NONE/PENDING/NEED_MORE/REJECTED/APPROVED` + `reviewReason`；按**最新**一条非作废修订归并，新修订待审时显示 `PENDING` | `BizPayServiceController.java` 49 行 `GET /biz/merchant/pay-service`；near-pay `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/platmch/source/SourceApplicationStatus.java` 38–66 行 | N2 前置判断、N1 守卫、N3 A8、C08 渠道一格 |
| 8 | near-pay 接收 `materials[{kind, ref}]` | near-pay `backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/platmch/InternalSourceLinkController.java` 82–87 行 | N3 证照走 materials（值域见第 24 行） |
| 9 | 渠道进件 / 补件（结算帐户、证照） | `BizMerchantController.java` 788 行 `POST /biz/merchant/payment` → `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantPaymentServiceImpl.java` 162 行 `submit` | **台湾不可用**：唯一的进件网关实现是 `StubApplymentGateway`，没有网关时抛 70045（`MerchantPaymentServiceImpl.java` 498–514 行）。所以 N3 的结算帐户、J02 补件都不能借这条路 |
| 10 | 主体级收款类路由走 foundation 通道（绑定 `FoundationTarget`） | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` 122–145 行（`/biz/merchant/payment`、`pay-channel`、`pay-service*`、`/biz/merchant/apply`、`agreement/accept`、`/biz/qualifications*`、`/biz/upload/image`）；注释写明「申请支付服务」要主体级目标 | N2、N3 照此登记 |
| 11 | 门店换收款号（只能挑本主体 `ACTIVE` 的号，门店专属号不能给别的店） | `BizMerchantController.java` 973 行 `POST /biz/store/{storeNo}/payment` → `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreAdminServiceImpl.java` 297 行 `setPayment`；路由在 `ShopStoreMemberRoutes` 37 行 | N1 的同类先例（权限码、路由、留痕、`payMirror.changed`） |
| 12 | 判断「这个主体是不是我当店主的」，不是就 403、不回落 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantEntityServiceImpl.java` 98 行 `requireOwned` | N1 目标主体校验 |
| 13 | 主体列表 / 详情（只给店主）；`EntityVO` 只有 `entityNo, name, status, verified, legalForm, storeCount, isPrimary, canManage`，`status` 是主体生命周期（`ACTIVE/PENDING_LICENSE/SUSPENDED/BANNED`），没有审核态 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizEntityController.java` 57、68 行（`biz:store:admin`）；`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/EntityVO.java` 22 行；路由 `ShopStoreMemberRoutes` 30–31 行 | N4 在此追加字段 |
| 14 | 资质列表 / 保存 / 识别 | `BizMerchantController.java` 535、580 行；`/biz/qualifications/recognize` 在 `BizCertController` | N3 不直接调，App 拍照识别仍用它 |
| 15 | 上传图片（`bizType=QUAL` 落 `{主体}/_ENTITY/qual/{年月}/{随机名}`，回私有相对路径 `url` 与 `assetKey`；上传本身不写 `mch_qualification`） | `backend/shop-channel/src/main/java/ai/neargo/shop/channel/media/api/BizUploadController.java` 89 行、150–155 行（key 结构）、176–179 行（返回值） | N3 证照、N2 证明都传 `assetKey`；M6 |
| 16 | 结算 / 提现收款账户（加密存、只回掩码、运营审核） | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizPayoutAccountController.java` 40、52 行；表 `V358__supplier_payout_account.sql` | **不适合 N3**：它是自营模式下平台代付货款的卡，要求户名 = 主体名（三流一致）；台湾渠道直清的结算帐户要进渠道进件资料 |
| 17 | near-pay 渠道账户只能由运营建、核实、启用 | near-pay `backend/pay/pay-portal/src/main/java/ai/neargo/shop/pay/api/ops/v2/OpsChannelAccountController.java`（`/pay-ops/v2/channel-accounts`，`POST`、`/{payMerchantNo}/verify`、`/activate`）；商户端 `.../api/biz/v2/BizChannelAccountController.java` 只能列表和把已有账户绑到门店 | N2 后端没有商户自助入口 |
| 18 | near-pay 门店来源主体变了**不自动换关联**，只记 `ENTITY_CHANGED`，换关联只走运营 | near-pay `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/platmch/store/StoreProjectionService.java` 32、202–216 行 | N1 必须限定在「门店从未在 near-pay 关联过」 |
| 19 | 商城订阅账户按门店唯一、带 `entity_no` | `backend/commerce/commerce-persistence/src/main/resources/db/commerce/migration/V3__commerce_subscription_credit.sql` 2–18 行 `mch_subscription_account` | N1 改挂要么同步改，要么拦（本文选拦已付费、同步改 FREE，见 §4） |
| 20 | 新模型门店准入测试、端点权限登记测试 | `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/StoreScopedDailyRoutesFlowTest.java`（`payServiceApplyPassesAdmission` 等）；`backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` 689–690 行 | §5 测试沿用 |
| 21 | 新模型下不带门店头提交入驻申请 → 70198 `STORE_BUSINESS_TYPE_REQUIRED`；不带门店头时 `FoundationTarget.applicationEntity` 回空主体 | `BizMerchantController.java` 314 行（`commerce.store-creation.enabled` 为真时）；`backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/FoundationTarget.java` 30–43 行；开关现状见 `docs/technical/商城上线开关与商品清单.md` 48 行（本机、166 已开） | 评审 R1：M04 在新模型下本来就提交不了 |
| 22 | 改进行中的入驻申请：只有 C 端在用，只认 `PENDING`（否则 10467 `APPLY_NOT_EDITABLE`），**不改证照**（`qualifications`、`qualification_items` 不在 set 里）；已核准主体没有任何改名称 / 主体类型的入口 | `OpsServiceImpl.java` 794–840 行 `updateApply`；调用方只有 `backend/shop-app/src/main/java/ai/neargo/shop/portal/mp/MpCatalogController.java` 341 行 | 评审 R2、R8：N3 修改路径要一个带证照的新方法 |
| 23 | 主数据快照：行业（`industry, name, microAllowed`）与主体（`subjectType, needLicense, industryGated`）一次返回，就是给「选行业 → 过滤主体 → 要不要执照」这一屏用的 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/api/common/CommonMetaController.java` 37–38 行 `GET /common/master-data`；`backend/shop-core/src/main/java/ai/neargo/shop/platform/dto/MasterDataVO.java` 36、59 行；服务端判据 `OpsServiceImpl.java` 767 行 `requireSubjectAllowedByIndustry` | 评审 R10、M1：`industry` 送这里的码 |
| 24 | near-pay 资料材料只收 `kind ∈ {BUSINESS_LICENSE, ID_DOCUMENT, BANK_ACCOUNT, OTHER}`、`ref` 匹配 `^(file\|cipher):[\w./:#@-]{1,249}$`，最多 20 个；材料计入资料摘要 | near-pay `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/platmch/profile/ProfileMasking.java` 22–23、57–80 行 | N3 不能送图片 URL，只能送 `file:<assetKey>` |
| 25 | near-pay 按来源修订号判重：同一 `entityRevision` 载荷不同 → 冲突；同一 `profileRevision` 资料摘要不同 → 冲突；修订号更低 → `STALE`、资料不提交；同一事件号参数不同 → 冲突。冲突回 409 | near-pay `SourceLinkService.java` 121、157–162 行；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/platmch/profile/ProfileRevisionService.java` 51–55 行；状态码 `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalServiceResponses.java` 75–80 行（`CONFLICT → 409`） | ai-shop 送的两个修订号都 = `mch_entity.updated_at` 毫秒（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/paysync/StoreSnapshotReader.java` 48–53 行）。主体不变而资料变了（改错字、补件）就会撞冲突 —— 评审 R2、R8 的另一半 |
| 26 | near-pay 已批准的资料修订在新修订待审期间保留 | near-pay `ProfileRevisionService.java` 83 行 `latestApproved`（送件前置读它） | 已拍板 2 的后果分析 |
| 27 | 业务异常统一回 `ApiResult<Void>`：只有 `code` 与文案，不带数据 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/common/GlobalExceptionHandler.java` 48–60 行；`backend/shop-base/src/main/java/ai/neargo/shop/common/BizException.java`（只有 `errorCode`、`args`） | 评审 R9 的 `data.reason` 不照改，改预检端点 |

**后端没有**：一次提交资料的端点；绑定已有渠道商户号的任何入口（ai-shop 与 near-pay 商户端都没有）；门店换主体的任何入口（`入驻进件建店-需求与四层对齐.md` 277 行「换主体 ⬜」，`门店注册到进件-流程节点与代码对齐.md` 158 行「产品明确暂不做」）；`ID 证件`类资质码（`qual_type` 值域只有 `BUSINESS_LICENSE/FOOD_PERMIT/FOOD_WORKSHOP/OTHER`，见 `V202__auth_code_qual_type.sql` 11 行）；改已核准主体名称 / 主体类型的入口；银行代码清单接口（ai-shop 与 near-pay 都没有商户端 `bank` 相关端点）。

### 建议接口能不能用已有接口组合

**N3：大半能。** 如果产品不想要新端点，App 按下表串三个已有接口即可交付除结算帐户、改进行中申请的证照、补件重提外的全部内容：

| App 字段 | 已有接口与字段 | 备注 |
|---|---|---|
| `profile.name` | ① `POST /biz/merchant/apply` `name`；③ `pay-service/apply` `legalName` | |
| `profile.subject` | ① `subject` | **`SOLE` 要改送 `INDIVIDUAL`**（或旧码 `INDIVIDUAL_BIZ`），`COMPANY`/`PERSONAL` 可原样送 |
| `profile.taxNo` | ① `qualificationItems[{type: BUSINESS_LICENSE, code: taxNo, imageUrl: 登记证照片}]`；③ `registrationNo` | apply 没有统编字段，只能放在资质的证号上 |
| `profile.owner` / `ownerPhone` | ① `contactName` / `contactPhone`；③ `representativeName` / `contactPhone` | |
| `profile.address` | ③ `registeredAddress` | apply 没有地址字段 |
| `profile.industry` | ① `industry`（`/common/master-data` 的行业码） | 决定可选主体类型（`requireSubjectAllowedByIndustry`） |
| `licenses[COMPANY_REGISTRATION\|BUSINESS_REGISTRATION]` | ① `qualificationItems` 的 `BUSINESS_LICENSE` 一项 | |
| `licenses[OWNER_ID_FRONT\|OWNER_ID_BACK]` | ① `licenses[]`（纯图片 URL 列表） | 没有证件类资质码；③ 现在不送材料 |
| `agreed` | ② `POST /biz/merchant/agreement/accept`（必须在 ① 之后，它作用于最近一张申请） | |
| `channels[]` | 无 | near-pay 申请不分渠道，按市场开 |
| `settleAccount` | **无** | 见 §1 第 9、16 行 |
| `entityNo` / `storeNo` | ① 体内 `entityNo`、`storeNo`（与门店头一致校验） | |

**本文仍建议新增 N3 端点**（§3 方案 A），理由：结算帐号明文只该出现在一次请求里，拆给 App 串接就要 App 持有并多次发送；`SOLE` 映射、证照拆分、near-pay 材料引用与修订号（§1 第 24、25 行）是服务端规则，放 App 会和 C 端入驻走偏；三步之间失败时（例如申请成功、near-pay 失败）由服务端给一个统一的「哪步成了」比 App 自己拼更可靠。组合方案作为后备写在已拍板 9。

**N4：不能。** `EntityVO` 没有审核态（§1 第 13 行），App 只能另读 `/biz/merchant/apply` —— 而那个只回本人最近一张，不分主体（`OpsServiceImpl.java` 858 行 `myApply`），多主体时对不上。

**N2：不能。** ai-shop 没有；near-pay 只有运营端建渠道账户（§1 第 17 行）。

**N1：不能。** `POST /biz/store/{storeNo}/payment` 只在本主体已开通的号里换（`StoreAdminServiceImpl.java` 297–323 行），跨主体会被 10400 拦。

### 会被改到的（已在跑的功能）

- `PayServiceApplyRequests.build` / `Form`：加可选字段，**事件号摘要保持只算原来的五个字段**，否则已提交的主体重发会得到新事件号（被当成资料变更）。新字段变化靠修订号区分（§2 N3「near-pay 修订号」）。
- `OpsService`：新增 `reviseMerchantApply`；`updateApply`（C 端）不动。
- `EntityVO` 追加字段；`/biz/entities`、`/biz/entity/{entityNo}` 组装多一次批量查询。
- `MerchantPaymentServiceImpl#refresh` / `#list` / `#availableChannels`：多认一种 `source=BIND` 的行。
- `CommerceRoutePolicy`（commerce starter）、`ShopStoreMemberRoutes`：各加路由。

### 明确不受影响的

- C 端入驻 `POST /mp/merchant/apply` 与 C 端改申请 `updateApply`、运营代填 `createApplyOnBehalf`、运营审核 `activate`：N3 只是又一个调用 `createMerchantApply` 的入口，加一个只给 N3 用的改申请方法。
- 旧进件 `POST /biz/merchant/payment` 的状态机：N2 的行用 `source` 区分，`APPLY` 行逻辑不变。
- 运营端 `POST /ops/merchant/{entityNo}/pay-service/apply`：共用组装函数，只是新字段为空；修订号仍 = 主体 `updated_at`，N3 刷新过的主体，运营端随后提交用的也是同一个更高的值，不会 `STALE`。

---

## §2 方案

### 契约变更总表

- 端点：新增 `POST /biz/merchant/onboarding`、`POST /biz/merchant/payment/bind`、`POST /biz/store/{storeNo}/entity`、`GET /biz/store/{storeNo}/entity/relink-check`；`GET /biz/entities`、`GET /biz/entity/{entityNo}` 响应追加字段
- 库表：`mch_payment_merchant` 加 `source`、`channel_merchant_masked` 两列（`V6xx__payment_merchant_bind_source.sql`，号待定）。N1、N3、N4 不加表
- 权限码：不新增。N3、N2 用 `biz:finance`，N1 与预检用 `biz:store:admin`，N4 沿用 `biz:store:admin`
- i18n：不新增错误码（全部复用 10400/10403/10404/10409/10467/10505/70001/70037/70045/70198），无新词条
- 配置项：无
- 跨服务契约（near-pay，须 near-pay 侧另立 TDD）：`POST /internal/pay/v2/source-links/apply` 的 `profile` 加可选 `settlement`；`materials[].ref` 收 `file:<ai-shop assetKey>` 时 near-pay 运营端如何取图；新增 `POST /internal/pay/v2/source-links/channel-account-claims` 与查询

### N3 `POST /biz/merchant/onboarding`

| 项 | 值 |
|---|---|
| 头 | `Authorization`、`X-Store-No`（**必带**；不带 70198）、`X-Entity-No` |
| 权限码 | `biz:finance`（`@PreAuthorize("@perm.canBiz('biz:finance')")`，与 `pay-service/apply`、`/biz/merchant/payment` 同码） |
| 路由登记 | **`CommerceRoutePolicy` foundation 通道**：`case "POST /biz/merchant/onboarding" -> "biz:finance"`。**不登记 `ShopStoreMemberRoutes`**（登记在那里不会绑定 `FoundationTarget`，新模型门店会 70006 `CONTACT_ADMIN`，同 11/07 事故） |

门店头必带，是因为服务端判断不了「App 用哪家店当头」是不是用户本意：评审 R1 指出 App 的 M04 用 `merchantScope()` 随手取一家店，带到占位店就会把新资料挂到那家无关的店上。必带之后，**选哪家店是 App 在界面上让用户明确选出来的事**；M04 怎么留见已拍板 1。

请求：

```json
{
  "entityNo": "E…",               // 可空；给了必须等于门店主体
  "storeNo": "S…",                // 可空；给了必须等于 X-Store-No
  "profile": {
    "subject": "COMPANY|SOLE|PERSONAL",
    "name": "…",                  // 1–128
    "taxNo": "12345678",          // COMPANY/SOLE 必填 8 位数字；PERSONAL 忽略
    "owner": "…",                 // 1–64
    "ownerPhone": "+886912345678",// E.164：^\+[1-9]\d{6,14}$
    "address": "…",               // 可空，≤256；空则不送 near-pay registeredAddress
    "industry": "CATERING",       // 必填，GET /common/master-data 的 industries[].industry
    "industryNote": "…"           // industry=OTHER 时必填，≤64；否则忽略
  },
  "licenses": [{"type": "COMPANY_REGISTRATION|BUSINESS_REGISTRATION|OWNER_ID_FRONT|OWNER_ID_BACK", "assetKey": "E…/_ENTITY/qual/202611/….jpg"}],
  "channels": ["NEWEBPAY", "LINEPAY"],   // 见差异 3
  "settleAccount": {"bankCode": "812", "accountName": "…", "account": "1234567890"},
  "agreed": true
}
```

字段规则（评审 M1、M4、R10）：
- `industry` 是**入驻行业**（`sys_industry` 七类），不是门店的经营类型码 `industryCode/subIndustryCode` —— 两套码，后者必须等于 `businessTypeCode`（`BizMerchantController#requireIndustry`），送它会让小微主体 70001。App 表单读 `/common/master-data`：`subjects[].industryGated=true` 且所选行业 `microAllowed=false` 时置灰该主体类型，与服务端 `requireSubjectAllowedByIndustry` 同一判据，不必等提交后 70001。
- `address` 可空：入驻申请没有地址列，near-pay `registeredAddress` 可空。`fromStore=false` 时 App 可以不问。
- 必传证照：`COMPANY` = `COMPANY_REGISTRATION + OWNER_ID_FRONT + OWNER_ID_BACK`；`SOLE` = `BUSINESS_REGISTRATION + 两面证件`；`PERSONAL` = 两面证件（与 App `MerchantDraft.licenseTypes` 一致）。
- `assetKey` 取 `/biz/upload/image?bizType=QUAL` 返回的 `assetKey`，必须以「门店主体号 + `/_ENTITY/qual/`」开头 —— 防止拿别的主体上传的图凑资料。图片地址由服务端用 `MediaStore#privatePath(assetKey)` 算，与上传接口返回的 `url` 同一函数。

服务端映射（`MerchantOnboardingRequests`，纯函数）：

| 去向 | 来源 |
|---|---|
| `SubmitApplyCommand.subject` | `COMPANY→ENTERPRISE`、`SOLE→INDIVIDUAL`、`PERSONAL→MICRO` |
| `qualificationItems` | 一项 `{type: BUSINESS_LICENSE, code: taxNo, imageUrl: 登记证地址, expireAt: null, issuer: null}`（`PERSONAL` 没有这一项） |
| `licenses`（图片列表） | 全部证照地址 |
| `contactName` / `contactPhone` / `industry` / `industryNote` / `name` | `owner` / `ownerPhone` / `industry` / `industryNote` / `name` |
| near-pay `profile` | `legalName=name, registrationNo=taxNo, representativeName=owner, contactPhone=ownerPhone, registeredAddress=address, industry=industry, materials=[{kind, ref: "file:"+assetKey}], settlement={bankCode, accountName, accountNumber}` |
| near-pay `materials[].kind` | `COMPANY_REGISTRATION`、`BUSINESS_REGISTRATION` → `BUSINESS_LICENSE`；`OWNER_ID_FRONT`、`OWNER_ID_BACK` → `ID_DOCUMENT`（near-pay 值域见 §1 第 24 行） |

#### 分流（评审 R1、R2、R8）

先校验（A3），再按门店主体与申请的状态走一条：

| 门店主体 | 本地申请 | 动作 | 响应 `applyStatus` |
|---|---|---|---|
| `PENDING_LICENSE` | 本人没有进行中申请（从未申请，或本主体最近一张 `REJECTED`） | 新建申请（新 `applyNo`）+ 同意协议 + 刷新主体修订号 → 送 near-pay | `APPLYING` |
| `PENDING_LICENSE` | 本主体一张 `PENDING`，内容全同 | 重放：不写本地，只补发 near-pay | `APPLYING`，`replayed=true` |
| `PENDING_LICENSE` | 本主体一张 `PENDING`，内容不同 | `OpsService#reviseMerchantApply` 改这张申请（含证照，`applyNo` 不变）+ 同意协议 + 刷新主体修订号 → 送 near-pay | `APPLYING`，`revised=true` |
| `PENDING_LICENSE` | 本主体一张 `REVIEWING` | 10467 `APPLY_NOT_EDITABLE`（与 C 端改申请同一码） | — |
| `PENDING_LICENSE` | 本人在别的主体上有进行中申请 | 10409 | — |
| `ACTIVE` | 不看 | 只送 near-pay（补件 / 首次申请收款）：`subject`、`name`、`taxNo` 须与已核准主体的 `legal_form`、`name`、`BUSINESS_LICENSE` 证号一致，否则 10409；near-pay 状态须是 `NONE`/`NEED_MORE`/`REJECTED`，否则 10409；`agreed` 仍须为 `true`，但不写入驻申请 | `APPROVED` |
| `SUSPENDED` / `BANNED` | — | 10409 | — |

`reviseMerchantApply` 是新方法而不是放宽 `updateApply`：后者是 C 端在用的，它刻意不改证照、`serviceScope` 为空时保留原值，改它的语义会改到 C 端。新方法与新建走同一组闸（`requireSubjectAllowedByIndustry`、`requireLicenseIfNeeded`），只认本人、本主体、`PENDING`，写 `MERCHANT_APPLY_UPDATE` 审计。

J02 补件在新模型里就是「near-pay 状态 `NEED_MORE`（带 `reviewReason`）→ 用 N3 重提」：主体还在审 → 走修改；主体已核准 → 走 `ACTIVE` 一行。旧的 `mch_payment_merchant` 渠道行 `missing` 在台湾不会出现（§1 第 9 行）。

#### near-pay 修订号（评审 R2、R8 的另一半）

near-pay 按来源修订号判重（§1 第 25 行），ai-shop 送的修订号 = `mch_entity.updated_at`。占位主体在审期间名称不变、`updated_at` 不变，于是「改一个错字再提交」「补件重提」都会被 near-pay 判成「同一修订号、内容不同」而 409 —— 草案原文没处理，评审说的「卡死」在 near-pay 这一侧同样成立。规则：

1. 新建、修改两条路径在本地事务里 `UPDATE mch_entity SET updated_at = now` 刷新修订号，near-pay 随后收到更高的修订号，进新资料修订。
2. 任何路径（含 `ACTIVE` 补件、重放时只改了结算账户 —— ai-shop 不存结算账户，判不出它变了）near-pay 回 409，刷新修订号后**重发一次**；仍 409 → `payService.status=UNAVAILABLE` 并告警。
3. 原样重放不刷新：修订号、事件号都不变，near-pay 幂等返回。

不在每次提交都刷新：那样原样重放也会在 near-pay 叠一条待审修订，正是 §4 第二行要防的「重复待审」。

响应 `data`：

```json
{
  "entityNo": "E…",
  "applyNo": "MA…",                      // ACTIVE 路径为 null
  "applyStatus": "APPLYING|APPROVED",
  "agreedAt": 1762560000000,             // ACTIVE 路径为 null
  "replayed": false,
  "revised": false,
  "settleAccountMasked": "812 ****7890",
  "payService": {"status": "PENDING|NEED_MORE|REJECTED|APPROVED|UNAVAILABLE|NOT_ELIGIBLE", "merchantRef": "…", "applicationRef": "…"}
}
```

错误：10400 资料不合格（A3、`agreed=false`）；70198 没带门店头；70001 主体类型与行业不符（已有，`OpsServiceImpl.java` 767 行起）；10467 申请已进入审核、不能改；10409 本人别的主体有进行中申请、已核准主体的类型 / 名称 / 统编不一致、near-pay 审核中或已通过、主体停用；`AccessDenied(FORBIDDEN)` 头与体不一致；10403 不是店主；70037 主体数已满（已有闸）。near-pay 失败**不报错**，落在 `payService.status`。

幂等（评审 M7）：不新增幂等表，**不读 `Idempotency-Key` 头**（App 不必发，发了忽略）。重放判据是本主体 `PENDING` 申请上 `name、legalForm、industry、industryNote、contactName、contactPhone、qualificationItems、licenses` 全同。驳回后再提交是新单、`applyNo` 会变（评审 M8），App 的进度按 N4 的主体 `review` 显示，不按 `applyNo`。

与 App 建议的差异：

| # | 差异 | 理由 |
|---|---|---|
| 1 | 响应从「无」改为上面的对象 | 给出 `payService` 才能让 App 直接显示「审核中 / 未送达 / 暂不可申请」而不是再读一次 |
| 2 | `settleAccount` 从 `{bank, account}` 改为 `{bankCode, accountName, account}` | 渠道进件要户名；`bank` 是显示名不是代码（App 现在送的是界面上选的银行名） |
| 3 | `channels` 从 `[{payChannel, channelMerchantNo?}]` 改为字符串数组，且 `channelMerchantNo` 不收 | near-pay 申请不分渠道（`capabilityType=FIAT`）；已有商户号走 N2，是另一种审核（核实已有号 vs 新开户），混在一张单里失败时说不清是哪一半。v1 `channels` 只校验在本市场启用列表内并作为偏好透传，`JKOPAY` 直接 70045（文案见已拍板 7） |
| 4 | `entityNo`/`storeNo` 可空，门店头必带 | 由门店头决定，与 `/biz/merchant/apply` 一致；给了就必须一致 |
| 5 | `licenses[].imageUrl` 改 `assetKey` | near-pay 材料只收 `file:` 引用（§1 第 24 行）；`assetKey` 还带主体前缀，能校验归属 |
| 6 | `profile.industry` 送入驻行业码，不送门店的经营类型码；加 `industryNote` | 见上「字段规则」 |

### N4 主体读接口补审核字段

| 项 | 值 |
|---|---|
| 端点 | 已有 `GET /biz/entities`（列表）、`GET /biz/entity/{entityNo}`（详情的 `entity`） |
| 权限码 / 路由 | 不变：`biz:store:admin`，`ShopStoreMemberRoutes` 30–31 行 |

`EntityVO` 追加（只加不改，`status` 仍是主体生命周期）：

| 字段 | 取值 |
|---|---|
| `review` | `NONE\|REVIEWING\|APPROVED\|REJECTED`：主体非 `PENDING_LICENSE` → `APPROVED`；否则看本主体（`mch_entity_apply.entity_no`）最近一张申请：`PENDING`/`REVIEWING` → `REVIEWING`，`REJECTED` → `REJECTED`，没有 → `NONE` |
| `applyNo` | 本主体最近一张申请号，可空 |
| `rejectReason` | `review=REJECTED` 时为该申请的驳回原因，否则空串 |
| `taxNo` | 已核准：`mch_qualification` 中 `qual_type=BUSINESS_LICENSE` 的 `qual_number`；未核准：最近申请 `qualification_items` 中同一项的 `code`；都没有为空串 |
| `owner` | 最近申请的 `contact_name`，没有为空串 |

App 侧：`MerchantEntity.fromJson` 的 `review` 改读 `review`（不再拿 `status` 推 —— `PENDING_LICENSE` 永远推成 `none`，C08 正是因此出不来）。C08 时间线：「已提交 / 商户审核」读 `review`，「渠道开通」读 `GET /biz/merchant/pay-service`（`status` + `reviewReason`），**不读** `/biz/merchant/payment`（N3 不写渠道行，台湾那张表只会有 N2 的 `BIND` 行）。

组装放 shop-app：`mch_entity_apply` 在 shop-core，`EntityVO` 在 shop-merchant，两模块互不依赖。`BizEntityController` 取到 `EntityVO` 列表后，用新增的 `OpsService#latestAppliesOf(userNo, entityNos)` 一次批量查出最近申请再合并；`taxNo` 的已核准一支由 `MerchantEntityServiceImpl#assemble` 顺带读 `mch_qualification`。位置以 `ArchitectureTest` 为准。

### N2 `POST /biz/merchant/payment/bind`

| 项 | 值 |
|---|---|
| 头 | 门店头必带 |
| 权限码 | `biz:finance` |
| 路由登记 | **`CommerceRoutePolicy` foundation 通道**：`case "POST /biz/merchant/payment/bind" -> "biz:finance"`；不进 `ShopStoreMemberRoutes` |
| 前置 | `GET /biz/merchant/pay-service` 不是 `NONE`。App 入口放在 N3 成功之后或 M02 / C01 的渠道行上，**不放在 N3 表单里**（表单提交前调必然 10409，评审已确认） |

请求：`{"requestNo": "App 生成，≤64；每次打开弹框生成一次，重试沿用", "entityNo": "可空，须等于 X-Entity-No", "payChannel": "NEWEBPAY|LINEPAY", "channelMerchantNo": "≤64", "evidenceAssetKey": "可空，签约书或后台截图；/biz/upload/image?bizType=QUAL 返回的 assetKey，须属本主体"}`

`evidenceAssetKey` 用 `bizType=QUAL`（评审 M6）：落在主体的 `_ENTITY` 档、私有路径；上传本身不写 `mch_qualification`，不会被资质审核或过期扫描当成资质。送 near-pay 时为 `file:<assetKey>`。

响应 `data`：`PaymentApplymentVO` 一行（与 `/biz/merchant/payment` 同形状）：`applyStatus=APPLYING`、`submitted=true`、`canReceiveMoney=false`，另加 `source: "BIND"`、`channelMerchantMasked: "MS1****5678"`。

错误：10400 字段缺失、带了 `credential`、`evidenceAssetKey` 不属本主体；70045 渠道本市场不可用；10409 该渠道已有 `ACTIVE` 或在途行、或主体还没申请支付服务；10505 支付服务不可用（内嵌部署 / near-pay 失败，**本地不留行**）。`requestNo` 相同的重发返回同一行。

near-pay 依赖：新内部端点 `POST /internal/pay/v2/source-links/channel-account-claims {sourceScope, sourceEntityRef, requestNo, payChannel, channelMerchantId, evidenceRef, actorRef}` → `{claimRef, status: PENDING_VERIFY}`，以及 `GET …/channel-account-claims/{claimRef}`。运营在 near-pay 核实时建渠道账户、录密钥、`verify`（已有端点）。

与 App 建议的差异：去掉 `credential`（已拍板 6）；加 `requestNo`（§3 通用约定的幂等要求）；`evidenceUrl` 改 `evidenceAssetKey`；结果是「待核实」不是 mock 里的立即 `ACTIVE`；App mock 用的拒绝码 10422 在后端是 `STAFF_SCOPE_ON_FULL_ACCESS`（`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java` 244 行），**不能用**，改为 10400 / near-pay 审核拒绝后行上的 `rejectReason`。

### N1 `POST /biz/store/{storeNo}/entity` 与预检

| 项 | 值 |
|---|---|
| 头 | `X-Store-No` 必须等于路径门店；`X-Entity-No` 是**旧**主体 |
| 权限码 | `biz:store:admin`（同 `POST /biz/store/{storeNo}/payment`） |
| 路由登记 | **`ShopStoreMemberRoutes`**：`HostRoute.of("POST", STORE + "entity")`、`HostRoute.of("GET", STORE + "entity/relink-check")`（门店级动作，与「本店开通收款」同类） |

**预检** `GET /biz/store/{storeNo}/entity/relink-check?entityNo=目标`（评审 R9、M5）：

```json
{"allowed": false, "blockers": ["HAS_TRADES", "PAY_SERVICE_APPLIED"], "revokedStaff": 2}
```

| `blockers` 机器码 | 对应拒绝 |
|---|---|
| `TARGET_NOT_OWNED` | 目标不是本人当店主的主体（改挂时 10403） |
| `TARGET_NOT_ACTIVE` | 目标未核准（10409） |
| `SOURCE_NOT_PLACEHOLDER` | 门店当前主体不是占位主体（10409） |
| `HAS_TRADES` | 门店有订单、售后、线下收款或储值账户（10409） |
| `PAID_PLAN` | 门店有付费订阅期或商城订单（10409） |
| `PAY_SERVICE_APPLIED` | 占位主体在 near-pay 的申请状态不是 `NONE`（10409） |
| `APPLY_PENDING` | 占位主体有进行中的入驻申请（10409） |
| `PAY_SERVICE_UNKNOWN` | near-pay 状态读不到（改挂时 10505）；拿不准一律不放行 |

预检只读，`relink` 先调同一个 `check`，两者判据只有一份。不照评审建议把原因塞进错误包的 `data.reason`：本仓库业务异常统一回 `ApiResult<Void>`（§1 第 27 行），为一个端点改全局错误包会波及所有端点；预检同时给出「将失效几名员工」，App 的确认弹框正好要它。

**改挂**请求：`{"entityNo": "目标主体"}`。注意：控制器**不能**把体里的 `entityNo` 交给 `FoundationTarget#check` —— 它本来就和当前主体不同。

响应 `data`：`{"storeNo": "…", "entityNo": "新", "previousEntityNo": "旧", "payReady": true, "revokedStaff": 2, "placeholderRetired": true, "stores": [/* 与 GET /biz/stores/mine 同形 */]}`。`stores` 在事务提交后用 `MerchantEntityService#myStores` 组装（评审 M9），App 据此按新的 `entityNo` 重新选店、换掉缓存的 `X-Entity-No`，不必再读一次。

前置条件（全部满足才改，任一不满足 10409，目标不是自己的 10403，门店不在旧主体下 10404）：

1. 门店当前主体是 `PENDING_LICENSE`，且当前用户是它的在职店主
2. 目标主体 `ACTIVE`，当前用户是它的在职店主（`requireOwned`）
3. 门店没有任何订单、售后、线下收款、储值账户
4. 门店订阅只有 FREE（没有付费期、没有商城订单）
5. 占位主体在 near-pay 的申请状态是 `NONE`（门店从未在 near-pay 关联过，见 §1 第 18 行）
6. 旧主体没有进行中的入驻申请（否则审核通过时会把申请升级到一个已经没有门店的主体上）

同一事务里做：改 `mch_store.entity_no`、`is_default=false`、`pay_merchant_no=null`、`payment_changed_at`；FREE 订阅账户的 `entity_no` 跟着改（经 commerce 端口）；删除该门店非店主的 `mch_store_role`；占位主体下没有门店了则逻辑删除占位主体并停用其成员行；`OpsService#audit(…, critical=true, …)` 记 A→B；提交后 `payMirror.changed` 触发门店同步。

与 App 建议的差异：App 允许选「审核中 / 被驳回」的主体（`store_payment_page.dart` 选项过滤 `e.review != MerchantReview.none`），本文只允许 `ACTIVE`（已拍板 5）；App 改挂前先调预检，确认弹框写明将失效人数；改挂后用响应里的 `stores` 按新 `entityNo` 重新选店（光调 `reloadStores()` 不刷新 `controller.selected`，后续主体级请求仍带旧 `X-Entity-No`，会被 `FoundationTarget` 拒绝）。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantOnboardingController.java` | N3、N2 两个端点（只有 `/biz/merchant` 一个资源前缀，不碰控制器内聚棘轮） |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/MerchantOnboardingRequests.java` | 校验与映射纯函数（主体码、证照拆分、`assetKey` 归属、near-pay profile 与材料） |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/MerchantOnboardingOrchestrator.java`（或放 `payclient` 包，实现时按 ArchitectureTest 定位） | 分流、本地事务、刷新主体修订号、远程调用与 409 重发 |
| 修改 | `backend/shop-core/src/main/java/ai/neargo/shop/platform/OpsService.java`、`impl/OpsServiceImpl.java` | 新增 `reviseMerchantApply`（含证照）、`latestAppliesOf(userNo, entityNos)`；`updateApply` 不动 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayServiceApplyRequests.java` | `Form` 加 `industry, materials, settlement`（可选）；摘要不变 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/PaySourceLinkApi.java` | `Profile` 加 `settlement`；新增 `claimChannelAccount`、`claimStatus` |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/EntityVO.java`、`service/impl/MerchantEntityServiceImpl.java` | 追加 `review, applyNo, rejectReason, taxNo, owner`；已核准主体的 `taxNo` 读 `mch_qualification` |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizEntityController.java` | 合并最近申请得出 `review` 等字段 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreEntityController.java` | N1 改挂与预检（不加进 1152 行的 `BizMerchantController`） |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/StoreRelinkService.java` + `impl/StoreRelinkServiceImpl.java` | N1 规则（`check` / `relink`）与事务 |
| 新增 | `backend/shop-base/src/main/java/ai/neargo/shop/spi/merchant/StoreRelinkGuardPort.java` | 「这家店有没有交易 / 付费订阅」只读端口；实现分别在订单域（shop-core）与 `commercebridge` |
| 新增 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantChannelBindService.java` + impl | N2 本地行 |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantPaymentServiceImpl.java`、`entity/MchPaymentMerchant.java` | `source` / `channelMerchantMasked`；`refresh` 对 BIND 行查 near-pay |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__payment_merchant_bind_source.sql` | `ALTER TABLE mch_payment_merchant ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'APPLY' COMMENT 'APPLY 平台进件 / BIND 绑定已有商户号', ADD COLUMN channel_merchant_masked VARCHAR(64) DEFAULT NULL COMMENT '已有商户号掩码；明文不落库'`。166 上带 DDL，交接时标出由 root 先执行 |
| 修改 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | foundation 通道加两条 |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 加 `POST STORE+entity`、`GET STORE+entity/relink-check` |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 登记四个新端点的权限码 |
| 修改 | `known-app-backend-orphan.txt` | 四个新端点 uni-app b-app 不接，按 `pay-service/apply` 先例（151 行）登记 |
| 修改 | `docs/api/openapi-b-onboarding.json`、`docs/api/B端注册到进件-标准API文档.md` | 新端点与 `EntityVO` 新字段的契约 |
| 依赖 | near-pay 仓库（另立 TDD） | `settlement` 字段、`file:` 材料引用的解析、渠道账户认领端点与运营审核页 |

---

## §3 选型（2 档）

### N3 结算帐户放哪

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 透传给 near-pay `source-links/apply`，ai-shop 不落库 | 明文只在一次请求里（ADR-002 §5 同一原则）；渠道直清本来就要它 | 依赖 near-pay 加字段；near-pay 失败要商家重提一次 | ✅ 采用 |
| B 存 ai-shop `mch_payout_account` | 已有加密、掩码、审核 | 语义是平台代付卡、要求户名 = 主体名；台湾渠道直清用不上，等于造一份没人读的数据 | ❌ |
| C 先不收，审核通过后再补 | 不依赖 near-pay | App 四步表单要拆，商家多跑一趟 | ❌（作为 near-pay 赶不上时的后备，见已拍板 4） |

### N3 资料变了怎么让 near-pay 收（评审 R2、R8 引出）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 本地知道变了就刷新主体修订号；near-pay 回 409 再刷新重发一次 | 原样重放不叠修订；只改结算账户（本地判不出）也能通过；不加表 | 改了 `mch_entity.updated_at` 的含义（多了一种「资料重提」触发），要核它的其他读者 | ✅ 采用 |
| B 每次提交都刷新 | 最简单 | 原样重放也在 near-pay 叠待审修订，运营重复审 | ❌ |
| C ai-shop 存一份已送资料的摘要来判变没变 | 判得准 | 要加列；结算账户的摘要也是一种落库，与 A5 的口径冲突 | ❌ |

### N1 失败原因怎么给 App（评审 R9）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 只读预检端点，200 回 `blockers[]` | 不动全局错误包；顺带给出将失效人数（M5）；App 可以在打开确认弹框前就置灰 | 多一个端点；预检与改挂之间状态可能变（改挂仍会 10409，App 再调一次预检即可） | ✅ 采用 |
| B 错误包加 `data.reason` | 一次请求 | `GlobalExceptionHandler` 对所有 `BizException` 回 `ApiResult<Void>`，要改全局形状 | ❌ |
| C 每种原因一个新错误码 | 不改形状 | 新增 i18n 词条与三端映射，换一种原因就要加码 | ❌ |

### N2 已有商户号怎么进系统

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A App 收商户号 + 密钥，经 ai-shop 转 near-pay | 一步到位 | 密钥经过 App 与 ai-shop 两跳，日志、抓包、错误回显任何一处漏了就是渠道密钥泄露；近一半商户拿不准哪个是密钥 | ❌ |
| B 只收商户号 + 证明，运营在 near-pay 核实时录密钥 | 密钥只在 near-pay 运营端出现，复用已有 `verify` | 要运营动手，开通慢一天 | ✅ 采用（已拍板 6） |
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
| 刷新 `mch_entity.updated_at` 当修订号 | 有别的代码按它判「主体资料变了」而多做一次同步或通知 | 实现前 grep `mch_entity` 的 `updated_at` 读者逐一核；只在新建 / 修改 / 409 重发三处刷新 |
| 409 后刷新重发仍冲突 | 资料送不到 near-pay，商家反复重试 | 只重发一次，之后落 `UNAVAILABLE` 并告警，不循环 |
| 结算帐号进日志 | 一批商户银行帐号泄露 | 输入 record 重写 `toString`；请求日志对 `/biz/merchant/onboarding` 体脱敏；测试断言日志不含明文 |
| near-pay 运营端打不开 `file:` 材料 | 运营审核看不到证照，只能驳回 | near-pay TDD 先定 `file:` 引用的取图方式；没定之前，运营仍可在 ai-shop 运营端按申请单看图 |
| N4 列表多一次批量查询 | `/biz/entities` 变慢 | 一次 `IN (entityNos)` 查最近申请，主体数受 70037 上限约束 |
| N1 改挂后旧 `X-Entity-No` 仍在 App 缓存 | 后续主体级请求被拒，像「功能坏了」 | 响应带新 `entityNo` 与 `stores`；App 必须按它重新选店（交接给 App） |
| N1 漏掉某种「门店有交易」的数据源（如储值、团购） | 交易的结算方被改到新主体，税务问题 | 守卫端口按白名单列数据源，实现时逐表核；拿不准的一律算「有交易」 |
| N1 商城订阅账户的 `entity_no` 是 commerce 模块的数据 | 跨模块写 | 只允许 FREE；经 commerce 端口改，不在 shop-merchant 里直写 commerce 表 |
| N2 near-pay 端点没有按时提供 | App 正式包仍拦着 N2 | N2 单独冻结，不阻塞 N3、N4、N1 |
| 两套进件并存（旧 `apply + payment` 与 N3） | 同一主体两份资料 | N3 内部就是旧 `apply`，同一张申请表、同一唯一键，不会出现两份；旧 `/biz/merchant/payment` 在台湾本来 70045 |

---

## §5 对账三 · 实现 → 需求（测试，计划，未跑）

| AC | 测试方法（计划） | 跑过 | 验证点 |
|---|---|---|---|
| A1 | `MerchantOnboardingRequestsTest#mapsSubjectsToCanonicalLegalForms`、`#splitsRegistrationIntoBusinessLicenseItem`；`MerchantOnboardingFlowTest#submitCreatesPendingApplyOnPlaceholderEntity`、`#missingStoreHeaderIsStoreTypeRequired` | 未跑 | `SOLE` 落 `INDIVIDUAL`；申请 `entity_no` = 占位主体；无门店头 70198 且不写库 |
| A2 | `MerchantOnboardingFlowTest#agreedRecordsAgreementInSameTransaction`、`#notAgreedWritesNothing` | 未跑 | |
| A3 | `MerchantOnboardingRequestsTest#rejectsMissingLicenseBySubject`、`#rejectsTaxNoNotEightDigits`、`#rejectsAccountOutsideTenToSixteenDigits`、`#rejectsAssetKeyOfAnotherEntity`、`#rejectsUnknownIndustryAndOtherWithoutNote`、`#rejectsNonE164Phone` | 未跑 | |
| A4 | `MerchantOnboardingFlowTest#forwardsProfileMaterialsAndSettlementToNearPay`（起本地 HTTP 服务，同 `PayServiceApplyRequestsTest#sendsThroughPayClientWithCallerHeaders` 做法）；`MerchantOnboardingRequestsTest#mapsLicensesToNearPayMaterialKindsAndFileRefs` | 未跑 | `ref` 全部 `file:` 开头且匹配 near-pay 正则 |
| A5 | `MerchantOnboardingFlowTest#settleAccountNeverPersistedOrLogged`；`PayServiceApplyRequestsTest#digestIgnoresSettlementAndMaterials` | 未跑 | 查库、抓日志、事件号不变 |
| A6 | `MerchantOnboardingFlowTest#nearPayDownStillCommitsLocalAndReportsUnavailable`、`#replayResendsOnlyToNearPay` | 未跑 | |
| A7 | `MerchantOnboardingFlowTest#differentDataWhilePendingRevisesSameApply`、`#reviewingApplyIsNotEditable`、`#pendingApplyOnAnotherEntityIsConflict`；`OpsServiceImplTest#reviseMerchantApplyUpdatesQualificationItems`、`#updateApplyStillLeavesQualificationsUntouched` | 未跑 | `applyNo` 不变、证照已改；10467；10409；C 端行为不变 |
| A8 | `MerchantOnboardingFlowTest#activeEntityResubmitsOnlyToNearPay`、`#activeEntityWithDifferentNameIsConflict`、`#activeEntityWhilePayServicePendingIsConflict` | 未跑 | 申请表行数不变 |
| A9、B7 | `StoreScopedDailyRoutesFlowTest#merchantOnboardingPassesAdmission`、`#channelBindPassesAdmission`；`BizEndpointPermTest`（登记） | 未跑 | 去掉 foundation 登记后变红 |
| A10 | `MerchantOnboardingFlowTest#bodyEntityMismatchIsDenied` | 未跑 | |
| A11 | `MerchantOnboardingFlowTest#revisionBumpsWhenContentChanges`、`#replayKeepsRevision`、`#conflictBumpsAndRetriesOnce`、`#secondConflictReportsUnavailable` | 未跑 | 本地 HTTP 服务按 near-pay 规则回 409 |
| A12 | `MerchantOnboardingFlowTest#neverWritesPaymentMerchantRows` | 未跑 | `mch_payment_merchant` 行数不变 |
| D1–D3 | `BizEntityControllerTest#reviewDerivedFromLatestApplyOfEntity`、`#rejectedCarriesReason`、`#taxNoFromQualificationWhenActive`、`#taxNoFromApplyWhenPending` | 未跑 | 多主体时各取各的申请 |
| D4 | `BizEntityControllerTest#existingFieldsUnchanged` | 未跑 | |
| B1 | `MerchantChannelBindServiceTest#bindCreatesApplyingRowWithMaskOnly` | 未跑 | 库里无明文 |
| B2 | `MerchantChannelBindServiceTest#disabledChannelIsUnavailable` | 未跑 | 70045 |
| B3、B4 | `MerchantChannelBindServiceTest#activeOrInFlightChannelIsConflict`、`#withoutPayServiceIsConflict` | 未跑 | |
| B5 | `MerchantPaymentServiceImplTest#refreshBindRowReadsNearPayClaim` | 未跑 | |
| B6 | `BizMerchantOnboardingControllerTest#bindRejectsCredentialField` | 未跑 | 10400 |
| B8 | `MerchantChannelBindServiceTest#evidenceOfAnotherEntityIsRejected` | 未跑 | 10400 |
| C1 | `StoreRelinkServiceImplTest#relinksPlaceholderStoreToOwnedActiveEntity` | 未跑 | `entity_no`、`is_default`、`pay_merchant_no`、审计 |
| C2 | `StoreRelinkServiceImplTest#foreignTargetIsForbidden`、`#inactiveTargetIsConflict` | 未跑 | |
| C3 | `StoreRelinkServiceImplTest#nonPlaceholderSourceIsConflict` | 未跑 | |
| C4 | `StoreRelinkServiceImplTest#storeWithOrdersIsConflict`、`#paidSubscriptionIsConflict`、`#placeholderAppliedToNearPayIsConflict`、`#pendingApplyOnPlaceholderIsConflict` | 未跑 | |
| C5 | `StoreRelinkServiceImplTest#revokesStaffRolesAndRetiresEmptyPlaceholder` | 未跑 | |
| C6 | `StoreRelinkServiceImplTest#triggersStoreSync` | 未跑 | |
| C7 | `StoreRelinkServiceImplTest#alreadyOnTargetIsIdempotent` | 未跑 | |
| C8 | `StoreScopedDailyRoutesFlowTest#storeRelinkPassesAdmission`、`#storeRelinkCheckPassesAdmission`；`BizEndpointPermTest` | 未跑 | |
| C9 | `StoreRelinkServiceImplTest#checkListsEveryBlockerWithoutWriting`、`#relinkUsesSameCheck` | 未跑 | 预检前后库无变化；每个 blocker 都有对应的改挂拒绝 |
| C10 | `BizStoreEntityControllerTest#relinkReturnsFreshStoreGroups` | 未跑 | 与 `/biz/stores/mine` 同形 |

闸门（实现后要跑）：`ArchitectureTest`、`BizEndpointPermTest`、`StoreScopedDailyRoutesFlowTest`、commerce starter 测试、`check-app-backend-orphan.py`、`SchemaDriftTest`（迁移）、`BackendI18nParityTest`（确认无新码）。

---

## §6 对账二 · 设计 → 实现

未实现，留空。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 草稿；待「已拍板」 |
| 2026-11-08 | 按商户 App 评审（TDD评审-商户App.md）修订：N3 必带门店头（无门店 70198）并按门店主体状态分流——占位主体的进行中申请可修改（10467 拦已进审核）、已核准主体只补收款资料（J02）；补 near-pay 修订号规则（变了才刷新、409 刷新重发一次）；证照改送 `assetKey`、near-pay 材料用 `file:` 引用；`industry` 改为入驻行业码、可选集读 `/common/master-data`；新增 N4 主体读接口补 `review/applyNo/rejectReason/taxNo/owner`；N1 加只读预检回 `blockers[]` 与将失效人数，改挂响应带新门店分组；N2 前置与入口写明、证明改 `evidenceAssetKey`；`data.reason` 与 `industryCode` 两条按后端事实未照改；原待决与新产品问题并入「已拍板」 |
| 2026-11-08 | 用户按「已拍板」各条建议全部接受，状态改为已确认；ADR 见文首 |

---

## 已拍板

> 2026-11-08 用户按建议全部接受：下列各条一律按「建议」执行；「选项」与「后果」保留作决策记录。

| # | 问题 | 选项与后果 | 建议 |
|---|---|---|---|
| 1 | **原型 M04「新建商戶資料（不預填門店資訊）」留不留？**（评审 R1）新模型下不带门店的申请后端本来就拒（70198，§1 第 21 行）；带一家随手取的门店又会挂错 | ①删掉 M04，新执照只从「某家门店 → C04」进：零后端，用户要先有一家占位店；②M04 改成「先开一家新店再填资料」：App 先调已有的 `POST /biz/merchant/quick-start`（要店名与经营类型）拿 `storeNo`，再带它调 N3 —— 但 quick-start 在本人已有占位主体时原样返回那一个，等于又回到「挂到已有占位店」，界面要说清楚；③后端支持不带门店的申请：要改 `activate` 在新模型下建一个没有经营类型的主体，与「每家店都有经营类型」的准入（`ShopCommerceEndContextAdapter#strictScope` 对缺类型回 `CHECK_UNAVAILABLE`）冲突，一期做不完 | ①。M01 列表的按钮改为引导「到门店的收款页提交资料」；有多家占位店的情况在新模型下不会出现（一人一个占位主体） |
| 2 | **已核准主体能不能改名称 / 主体类型 / 统编？near-pay 已通过后能不能改收款资料？**（评审 R2，原型 v5 待定 ③「重審、照常收款」） | ①一期不开放：N3 对已核准主体只接受与核准一致的名称、类型、统编，near-pay 已通过后也拒；改资料走客服 / 运营。后果：M02「查看與修改資料」只能看；②只开放收款资料（负责人、电话、地址、证照、结算账户）在 near-pay 已通过后重提：near-pay 已批准修订在新修订待审期间保留（§1 第 26 行），收款不断；但 `GET /biz/merchant/pay-service` 会按最新修订显示 `PENDING`（`SourceApplicationStatus` 62 行），App 要另写「资料变更审核中、照常收款」；③连名称 / 类型也开放：ai-shop 没有改已核准主体的入口，要新做「变更申请」与运营审核，牵动纳税主体与已开的发票 | 一期 ①；收到真实需求后做 ②（只需放开 A8 的「near-pay 已通过」一条、App 补文案） |
| 3 | **`PERSONAL`（无统编自然人）在台湾能不能开收款？** `pay-service/apply` 要求统编，没有就 10400 | ①一期只建入驻申请、不发 near-pay（`payService.status=NOT_ELIGIBLE`），App 选 `PERSONAL` 时 C06 渠道与结算一步隐藏并提示「個人身分暫不支援線上收款」；②near-pay 支持无统编登记：需 near-pay 与渠道确认个人可进件 | ①；与 `TDD-入驻分阶段与进件简化` §5.2「无照个人自营模式下不开放」口径一致，请产品确认台湾是否同样 |
| 4 | **结算帐户：near-pay 能否在 `source-links/apply` 加 `settlement`？户名是否必须等于主体名？银行代码清单从哪来？**（评审 M2） | 字段：能则透传（§3 方案 A）；赶不上则 N3 先不收 `settleAccount`，App 第四步改为「审核通过后补填」。户名：要求等于主体名可防代收，但个人 / 行号户名常有简繁与空格差异会误拒；不要求则交给渠道审核。银行清单：两侧都没有接口，可 App 写死（新增银行要发版），或 near-pay 出一个只读清单接口 | 能透传就透传，不临时存 ai-shop；户名不在 ai-shop 校验，由 near-pay / 渠道审核判；银行清单一期 App 写死台湾常用行并允许手输三位代码，near-pay 有清单后再切 |
| 5 | **N1 能不能选「审核中 / 被驳回」的主体？**（评审 R5）App 与原型 C02 现在允许（画了「王小明（個人）審核中」可选） | ①只允许 `ACTIVE`：审核中的主体可能被驳回，门店就挂在一张不会生效的执照下；App 列表把非 ACTIVE 的置灰并写「審核通過後可選」，**原型 C02 与 v5 文案要同步改**；②允许审核中：要处理「目标被驳回后门店怎么办」，以及审核通过时把申请升级到哪个主体 | ①，需产品签字并同步原型 |
| 6 | **N2 要不要在 App 收渠道密钥（`credential`）？**（评审 R6） | ①不收：只收商户号 + 可选证明，运营在 near-pay 核实时录密钥；C09 原型的 HashKey 输入框、「驗證失敗：商戶編號與密鑰不匹配」、当场「已綁定」都要重画为「送出核實 → 商戶號核實中」，原型待定 ⑤ 的上线时间顺延到运营核实流程就绪；②收：密钥经 App、ai-shop 两跳，任何一处日志或错误回显漏了就是渠道密钥泄露 | ①，需要一份 ADR；同步改 C09 原型与上线时间 |
| 7 | **`channels` 勾选是承诺还是偏好？**（评审 R7）near-pay 按市场开渠道，不按申请勾选 | ①偏好：v1 只校验、透传；原型 C03「勾選後隨商戶資料送件」与 C06 文案改为「希望開通（以平台核定為準）」；`JKOPAY` 后端无代码，选了 70045，App 应不展示；②承诺「只开勾选的」：需 near-pay 支持按渠道开通 | ①，并同步原型文案 |
| 8 | **门店改挂主体做不做？** 文档互相矛盾：`账号主体门店-关系定稿` §3 定「可切换」，`门店注册到进件-流程节点与代码对齐` 158 行写「产品明确暂不做」，`多门店与分享激励-需求` 四节列为「不做」 | ①一期只做「占位主体门店认领本账号已有主体」（§3 N1 方案 B）；②通用换主体：动纳税主体与 near-pay 关联，一期做不完；③不做：已有执照的老板开第二家店要再交一遍 | ①，需要一份 ADR 记下这个边界 |
| 9 | **要不要新端点？还是 App 串已有接口** | ①新端点（§1「组合」小节末的理由）；②App 串 `apply → agreement/accept → pay-service/apply`：结算帐户无处可去、改进行中申请改不了证照、补件重提撞 near-pay 修订号冲突（§1 第 22、25 行），这几条 App 侧补不上 | ①；后端排期不够时以 ② 先交付首次提交，结算帐户按已拍板 4 后补 |
| 10 | **身份证正反面是否进 `mch_qualification`？** | ①不进：只放申请单 `licenses` 与 near-pay `materials`（`ID_DOCUMENT`）；②进：它不是经营资质，会被上架闸门、过期扫描当成资质处理 | ① |
| 11 | **占位主体被认领后怎么处理？** | ①逻辑删除（`deleted=1`，`ShopCommerceStoreAdapter` 的 SQL 已按 `m.deleted=0` 过滤），成员行停用；②新增主体状态值：要改 B 端状态映射 `bizStatus` 与 App 枚举 | ① |
| 12 | **N1 撤销员工授权要不要先确认？** | ①要：App 先调预检拿 `revokedStaff`，确认页写明「本店 n 名員工的授權會失效，需在新商戶下重新授權」，后端只执行不询问；②不确认：员工突然进不了店，店主不知道原因 | ① |
| 13 | **旧进件 `POST /biz/merchant/payment` 在台湾怎么办？** 它只有 stub 网关，正式环境 70045 | ①App 正式包台湾市场不再走它，渠道状态以 `GET /biz/merchant/pay-service` 为准；`/biz/merchant/payment` 行状态与 near-pay 真实状态的同步另立题目；②继续走：台湾上线即 70045 | ① |


## 2026-11-10 N3/N4 增量执行（用户确认最新独立near-pay链路）

状态：本地实现/验证通过，166未访问或部署；旧N3渠道材料/结算账户透传设计由本节替代，N1/N2仍待实现。

- N4沿D1–D4，批量按已授权实体查当前账号最新申请，列表/详情追加审核字段，冷启动选店目录不额外公开申请详情；ACTIVE证号来自正式资质。旧status语义保持。
- N3仅提交平台商户资料/平台营业资质与协议，必选门店，按原foundation授权/头体一致校验。`profile`与`agreed`沿旧请求；`licenses`只收平台营业登记的assetKey，PERSONAL不要求渠道身份证。请求不接受非空结算账户或渠道证照，渠道敏感材料必须走已接入独立near-pay私有API。平台审核不代表渠道进件。
- 占位主体新建/同内容重放/同单PENDING修改，原一人一份唯一键、REVIEWING不可编辑、REJECTED新单及原审核激活语义保留；本地申请与协议同事务。ACTIVE不修改核准主体，由前端转进独立渠道旅程。
- 执行顺序：N4 DTO/批量读取/前端解析与测试；N3平台编排/请求校验/正式准入；bapp平台表单与私有渠道入口分离；集中相关编译/测试与本地验证；回填矩阵。
- 无新表/迁移、权限码或配置。旧source-links/apply不加settlement，亦不接收渠道证照；独立near-pay业务合同保持。


### 本批三处对账与验证

| 本批AC | 场景/API/数据落点 | 验证 |
|---|---|---|
| P1 平台新建/协议原子提交/同内容重放/同单修改 | N3→OpsService.submitMerchantProfile，原申请表/active-owner唯一键，事务内改PENDING/协议；不新建渠道行 | `MerchantPlatformOnboardingFlowTest#createReplayReviseAndReviewLockKeepOneApplicationAndAgreement` |
| P2 渠道敏感信息不进入平台N3 | Submit DTO拒非空settleAccount/非空channels；平台表单仅营业登记assetKey，不收渠道身份证/银行户 | `#privateChannelFieldsAndMissingAgreementAreRejectedBeforeWriting`；Dart `merchant_platform_contract_test.dart` |
| P3 显式门店/头体/所有权 | foundation finance与原店主查询；缺店70198、错实体拒绝 | `#missingStoreAndMismatchedEntityCannotWrite`；实际Dart HTTP错实体403 |
| P4 驳回回显/新单重提/本人主体范围 | OpsService.latestAppliesOf(user,authorized entityNos)批量取最新；EntityReviewAssembler，列表/详情同字段 | `#rejectionReadbackAndResubmissionUseEntityLatestApplication` |
| P5 营业资质归属、申请证号/正式证号分流 | 平台assetKey受目录/归属约束；待审取申请BUSINESS_LICENSE、核准取正式mch_qualification | `#platformBusinessAssetsAreScopedAndTaxNumberUsesApprovedQualification` |
| P6 主数据与一人一份原约束 | 入驻行业/启用主体主数据检查；另主体在途10409，REVIEWING不可编辑 | `#unknownIndustryAndAnotherEntityActiveApplicationCannotBeSubmitted`及P1 |
| P7 端上分离渠道与平台表单 | 三步表单、N4权威review、不用生命周期推审核；渠道补件不再进入N3，不拿A店提交B主体预填 | Dart2项合同、平台表单widget、`channel materials never reopen a cross-entity platform form` |

实现文件：EntityVO追加5字段并保留8参数构造；OpsService/Impl新增提交与按主体批量读方法；BizEntityController只编排已授权结果，EntityReviewAssembler跨模块组装；新BizMerchantOnboardingController使用框架输入约束/原foundation；CommerceRoutePolicy仅加精确N3路径。与原设计的偏差：正式资质批量读放应用组合层，不改MerchantEntityService构造和选店目录、不向stores/mine扩申请详情；PERSONAL映射使用当前权威NATURAL_PERSON，旧MICRO已淘汰。无DDL/依赖/权限码变化。

后端6项新增场景与12项准入守卫通过；6项场景最终日志`/tmp/ai-shop-platform-final-recheck.log`。首轮旧MICRO映射失败已修，企业用例仅因fixture缺qual_name/时间失败后补齐，未削弱表约束。前端合同2项、平台表单与跨主体补件2项widget通过，定向分析无问题。补件缺陷已先复现：旧入口可见；修后卡片/详情sheet均无平台补件按钮。

实际Dart→loopback ai-shop JVM→test-profile H2验证真实密码/身份票据登录、选店、平台提交、协议、重放、修订、N4列表/详情回读、错实体拒绝与退出；`MerchantPlatformLocalHttpTest`为本地驱动器，不重复计作第二条端上旅程。最终证据`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/bapp-platform-http-13761271846009594916/result.json`；私有ready文件已删除。H2不可标成MySQL或166。原统一身份→独立near-pay双JVM/MySQL9旅程因共享EntityVO/fixture变化补回归通过，证据`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/bapp-unified-nearpay-z4kpcwt7/result.json`，隔离库与私有文件清理完成。

`nextStep=PRIVATE_CHANNEL_ONBOARDING`只表示下一条旅程；不表示来源投影/typed成员授权已具备，不自动建支付商户、渠道申请或收款资格。来源与真实成员配置、渠道材料/OPS补件/真实渠道仍归T08/T10，独立near-pay合同不变。精确宿主来源与构建记录见进件发布闭包§10，未部署/提交/推送。


## 2026-11-10 N1 只读预检与写入前置复核

档位2，沿ADR055；本轮先实现可独立验证的GET预检，POST认领继续关闭。旧设计两项前提经真实代码核对不成立：host交易写入未共享门店锁；host支付申请RPC超时后near-pay可能晚提交，source status NONE又不查询门店关联历史。另PayMerchantMirror是旧支付商户双写，不等于StoreSyncPublisher；FREE period也冻结entity_no，不能只改subscription account。

只读实现顺序：已选店/USR/在职店主与头路径一致→目标本人ACTIVE/源PENDING_LICENSE→host订单/线下记录/在途平台申请→commerce订阅及商城历史→支付来源状态及依赖完整性→返回blockers/revokedStaff。各域通过自有只读接口提供事实，禁止跨模块直接更新表。GET不写审计/命令/认领/订阅/支付资料。

本批合同追加executionReady=false；allowed必须将RELINK_CONCURRENCY_UNAVAILABLE、FINANCIAL_HISTORY_UNKNOWN等真实能力缺口纳入阻断，不将snapshot干净等同于可原子执行。未提供完整历史证明时不能因为余额0或source NONE放行。POST不注册、不移除App写门禁；页面仅查看预检，不发改挂。写入需要补共享写侧门闩与支付申请持久在途/未知结果屏障（候选方案待用户范围回复），以及commerce纯FREE account+period同事务改属、员工撤权/空占位退休/真实投影同步。新增写表/恢复合同应另补ADR，不在此伪实现。


### 本批落点、AC与验证（只读子集）

- `StoreRelinkHostFactsPort`（shop-base）和core自有实现只查询订单/子单/售后/线下记录及在途平台申请；任意状态、软删、VOID、旧主体无法归属门店的行也计历史。显式tenant+store/entity，缺表不返回clean。
- 应用层`StoreRelinkPrecheck`组合已授权商户事实与支付来源状态；`BizStoreEntityController`只暴露GET，原USR+store-admin+源店主、路径/头/BizContext门店一致校验。跨主体查询不能用目标替换源头。distinct员工数不把一人多角色算多人。原写门禁不变。
- bapp只从本人ACTIVE实体中选择目标，查看只读blockers/人数；`review=APPROVED`不能替代生命周期ACTIVE。TW独立入口、迟到响应隔离，不POST、不换店、不启用渠道。四语增量文案已生成，原本地改动保留。

| 原AC | 本批状态 |
|---|---|
| C2/C3（来源/目标约束） | 只读诊断实现；跨主体无详情泄露，不能当执行验收 |
| C4（历史/依赖） | host已实现；完整PAY历史与commerce全历史明确UNKNOWN，不能从NONE/余额0放行 |
| C8（STORE_V1准入） | GET精确登记并实际通过；POST不登记 |
| C9（只读预检） | 已实现诊断子集，追加executionReady=false；allowed始终false并带明确缺口 |
| C1/C5/C6/C7/C10（迁移/撤权/同步/写回执） | 尚未实现，不因GET通过关闭 |

30项不同后端用例通过：core12、Spring/H2端点5、精确权限12、新增STORE_V1路由1。前端4项定向通过（首轮2项断言错误读取Dio独立query map，改检查实际URI query后仅重跑失败2项；GET/原头/目标/门禁断言未削弱）；定向分析无问题。实际密码/票据登录→选店→读取本人ACTIVE候选→GET诊断→外部目标拒绝→原门店主体不变→退出，Dart→本地JVM→H2通过，证据`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/bapp-platform-http-17146407855989602522/result.json`及同目录flutter-http.log（driver为通用平台本地驱动器，不重复计作第二旅程），私有ready文件已删除。

日志：`/tmp/ai-shop-relink-precheck-tests.log`、`/tmp/ai-shop-relink-runtime-tests.log`、`/tmp/bapp-relink-tests.log`（仅首轮2通过有效）、`/tmp/bapp-relink-tests-recheck.log`、`/tmp/bapp-relink-analyze-final.log`。未运行无关全量回归、未访问/部署166、未提交/推送。

### 写入与N2的后续工作（尚未实施）

1. 写侧门闩：订单/首次线下记录等交易写事务与认领共用门店身份锁并重验expected entity；主体级券/积分及服务订单也需纳入归属协议。当前只锁认领本身无法防止新历史并发进入。
2. 支付在途/未知结果屏障：BIZ/OPS所有来源apply应与claim串行，并持久记录已发/未知请求；RPC超时后不能因status NONE释放认领。还需明确是否采用host持久attempt屏障或PAY预留/恢复协议及已登记来源凭据的唯一调用方边界，涉及新表/恢复合同的范围问题已发用户，回复前不开放执行。
3. 完整金融历史证据：PAY需查source link真实行（merchant缺失不能降NONE）、projection坐标、所有association历史、pay/refund/store旧流水、零余额/取消/软删储值账户与充值；券/积分/品牌钱包和无法归属旧数据不完整即拒绝。现有organization-impact余额汇总不够。host `ord_offline_record`也必须保留。
4. commerce认领端口：锁原subscription account，确认无任意付费期/商城订单/credit/预留/转换历史后，同一事务更新纯FREE account及初始化FREE period的entity_no；不在merchant直接写commerce表。品牌绑定/待决品牌变更及金融关系需先明确不支持边界，不能默默转移。
5. 原子执行/回执：员工撤权、空占位逻辑退休、critical审计、真实StoreSyncPublisher投影同步、失败/未知恢复与旧X-Entity-No重选；C1/C5/C6/C7/C10需真实MySQL并发与本地端到端验收。
6. N2独立冻结：已在独立near-pay生产源码重新检索`channel-account-claims`/`ChannelAccountClaim`/`claimChannelAccount`，未有实现；当前15路由不含此能力。需先落独立near-pay TDD/typed成员权限、原requestNo恢复/运营核实和私有证明引用合同，再接宿主及App；不扩大白名单或借旧payment表/假ACTIVE。旧ADR056的host QUAL证明引用还需与最新私有证照链对齐；密钥仍仅在near-pay运营端录入，ai-shop只留掩码。


## 2026-11-10 N1/N2 写入与恢复实施定稿（用户确认执行）

状态：本批实现及本地验收完成（证据见文末），未部署。用户已明确批准“先修TDD/ADR，补N1写入及恢复，再N2，本地联调，不部署166”。本节替代只读阶段“扩展范围待回复”，N1范围仍沿ADR055。

### N1 数据/API与状态

宿主新增`mch_store_entity_claim`业务单（tenant、claim_no、owner_user_no、request_no、request_hash、source_entity_no、target_entity_no、store_no、expected_store_version、state、remote_ref、result_json、version、last_error等）；唯一(owner_user_no,request_no)，仅同key同hash重放。`mch_entity`/`mch_store`追加nullable `ownership_change_no`归属冻结指向业务单，无新实体生命周期枚举。新迁移预留V647，已应用迁移不改。

POST `/biz/store/{storeNo}/entity` 体 `{requestNo,entityNo:target,expectedStoreVersion}`；源店头/源entity与路径、个人USR店主+store-admin不变。GET既有relink-check追加当前门店版本/实际可执行能力。响应 `{claimNo,requestNo,state,storeNo,previousEntityNo,entityNo,stores?,revokedStaff?,placeholderRetired?}`；仅COMPLETED是成功迁移，PENDING/UNKNOWN不可伪成功。

恢复为精确ACCOUNT端点，不依赖已失效旧店头：GET `/biz/merchant/store-claims/by-request/{requestNo}`、GET `/biz/merchant/store-claims/{claimNo}`，POST `/biz/merchant/store-claims/{claimNo}/resume`；实际会话USR+claim.owner_user_no校验，GET无业务写；恢复只推进原单，不改目标/请求体、不重新授权新迁移。后台同一恢复实现按业务单状态推进，不建第二套任务真源。新建开关默认关闭，已有原单恢复不因新建关闭而丢失。

阶段：FROZEN→PAY_PREPARED→LOCAL_COMMITTED→COMPLETED；提交前明确失败先持久ABORTING→远端确认取消→ABORTED。远端结果未知保持原阶段和原requestNo查询/重放；不因TTL/source NONE自动解冻。LOCAL_COMMITTED后只向前完成支付确认，不自动回滚归属/员工。阶段转换由业务单行锁+version条件更新；ABORTING与LOCAL_COMMITTED互斥。

本地冻结短事务：固定序锁源/目标实体、门店与订阅，校验真实owner/status/version/完整历史，记录业务单与源实体/店冻结标记。认领阶段使用READ_COMMITTED短事务：等待已有写事务完成并获得排他锁后，历史查询读取已提交最新事实，避免先查幂等单形成旧RR快照。来源source_scope随原单持久化，恢复沿用原值，不随运行配置变化。相关写事务经`StoreOwnershipWritePort.lockAndCheck(tenant,expectedEntity,store)`或entity版本持共享锁并复核冻结/归属，先取得锁再写业务行；无实际事务不允许调用。认领排他锁与之串行，不在本地事务内等待远程HTTP。

PAY准备：固定可信来源+同requestNo/hash，真实全历史检查，持久预留并与来源apply共同锁定稳定source控制行；即使来源link尚不存在也有唯一锚点。独立PAY新增V77业务source control/claim intent（由PAY拥有），prepare/query/commit/abort沿internal可信来源鉴权。所有来源apply必须参加控制行锁；预留期间拒绝冲突申请。提交时CAS验证from/to/原projection revision并保留拒绝已有关联的旧保障，不从认领产生渠道ready。旧/乱序投影同步不能覆盖已确认新归属。

PAY接口前缀 `/internal/pay/v2/source-links/store-claims`：POST `/prepare`，GET `/{requestNo}`（sourceScope为query），POST `/{requestNo}/commit`/`abort`。prepare请求 `{requestNo,requestHash,sourceScope,sourceEntityRef,targetEntityRef,storeRef,storeNo,expectedRevision}`；storeRef为host数值id十进制字符串。结果 `{requestNo,state,claimRef,sourceEntityRef,targetEntityRef,storeRef,storeNo}`。配套GET `/internal/pay/v2/source-links/relink-check`只读历史与coverage，不用余额零/source status NONE代替完整史。请求hash含完整from/to/版本事实，不含渠道/结算明文。

PAY_PREPARED后本地事务再次校验被冻结实体/门店/owner/version及当前历史，原子改店主体/is_default/pay_merchant_no/payment_changed_at、经commerce改纯FREE account+初始FREE period、撤非店主store角色、空源主体逻辑删除/成员停用、critical审计，并记LOCAL_COMMITTED；保持店冻结。远端commit完成可信投影映射后，本地解除标记、保存最终目录回执、COMPLETED；使用既有StoreSyncPublisher同步恢复，不增加通用Outbox。目标未有支付来源/渠道时仍不可收款。品牌/金融/无法归属历史在未有明确安全迁移合同前作为明确blocker拒绝。

空源退休与多店留存：冻结时在源实体排他锁内统计未删除门店，记录不可变`source_retire_planned`；PAY prepare/commit/abort原命令追加`retireSource`。本地改挂后实际空源结果必须与该计划一致。PAY commit对退休源写永久tombstone，来源apply与实体级金融首次写入均拒绝迟到旧源请求；abort不退休。源仍有其他门店时释放实体预留，保留已认领门店的新主体约束，不阻止剩余门店经营。该事实随原单恢复，重放不可将true降为false。

### N2 按独立PAY真源实现

N1闭环后新增PAY商户渠道认领业务单（预留V79），原requestNo/hash/版本/查询结果恢复，状态待核实→核实通过/拒绝，核实通过也不自动等于渠道启用或门店ready。密钥仅PAY运营域；私有证明绑定支付商户及claim资源，不走host QUAL。宿主保留finance foundation/目标一致约束，PAY另验真实typed支付成员与渠道/来源归属；host只留必要掩码/引用，不再建立第二套权威绑定状态。旧APPLY状态机不改。仅新增合同明确的路径到白名单，不泛放现15路由。

### 优先实现与验收

1. 冻结N1持久化/接口，补业务写侧门闩、CORE/PAY/commerce历史和FREE领域移户能力。
2. PAY source申请互斥+预留/确认/取消/回执；保留既有SourceLink幂等事件，不复制造册。
3. 宿主分阶段原子执行/单向恢复/ACCOUNT原单查询，bapp持久原requestNo并只在COMPLETED重新选店。
4. MySQL9并发新交易/申请晚落/重复请求/进程重启/提交失败定向验收，实际App→双JVM本地旅程。
5. N2独立合同/私有证明/typed授权→运营核实→host/App接线与本地验收。

首批接口签名与状态按本节共享；实际代码若因已有基础设施可简化，回填偏差，不静默改变权限/冻结/未知结果语义。编译测试遵循Java规范§八增量约定，无166部署/远端写入、无自动提交/推送。

### N2 2026-11-10 实施接口定稿

本节替代旧host `mch_payment_merchant.source=BIND` 与QUAL证明路径。N2唯一业务真源在独立PAY；宿主和App不维护第二套绑定审核行。N1真实双端验收后依次实施下列合同。

- BIZ前缀 `/biz/pay/v2/channel-account-claims`。POST集合创建DRAFT：`{requestNo,payChannel,channelMerchantId,subMerchantId?,credential?}`，credential非空拒绝；GET集合、GET `/by-request/{requestNo}`、GET `/{claimNo}`。真实merchant和typed actor均取PAY Guard已校验成员，要求`store.bind`管理员能力，不扩大bootstrap。
- POST `/{claimNo}/files` multipart `{requestNo,expectedVersion,file}`，GET同资源列表及`/files/{fileNo}`鉴权读取。证明可选，创建草稿不要求证明；上传后归属tenant+environment+merchant+claim，AES-GCM/Vault封存，不能以假onboarding号复用进件表。图片类型/大小沿既有私有文件能力，提交原子检查版本/有效期并pin，响应不带公开地址。
- POST `/{claimNo}/submit`：`{requestNo,expectedVersion}`。DRAFT→PENDING_VERIFY；所有写命令保留原requestNo/hash/结果（含上传与submit），相同key变体拒绝，重放先于旧版本检查。GET `/{claimNo}/commands/{requestNo}`仅查询原结果，无业务写；未知先查原单/原命令，不重新生成请求号。
- OPS前缀 `/pay-ops/v2/channel-account-claims`，GET列表/详情/私有文件，POST `/{claimNo}/resolve`：`{requestNo,expectedVersion,result,payMerchantNo?,expectedAccountVersion?,reason}`，result为LINKED/REJECTED。关联仅选同tenant、同平台商户、同市场/渠道及完整MID事实的既有MERCHANT渠道账户；不建/核验/启用账户，不替代现有运营账户登记和审批。命令持久结果与critical审计同行事务。
- 持久状态DRAFT/PENDING_VERIFY/LINKED/REJECTED；LINKED表示运营确认了关联对象，不表示渠道核验通过。返回`claimNo,requestNo,merchantNo,payChannel,channelMerchantMasked,state,version,files,reason,payMerchantNo,accountVerificationStatus,accountStatus`，号码明文仅PAY内部存储。当前账户核验/状态从实际ChannelAccount读取，渠道核验仍走既有审批、异人复核；不因claim关联设置ACTIVE，不自动绑门店。门店可收款继续读取既有readiness。
- V79预留claim/命令回执/私有文件表，按实际PAY执行环境登记表分区约束；原申请唯一键包含tenant+environment+merchant+subjectType+subjectRef+requestNo，作用域错配不可读写。
- 宿主精确转发新增路径，原15路由不扩大。claim路径须运行真实DB会话鉴权、BizContext/commerce foundation admission和`biz:finance`，再通过`FoundationTarget`核对当前主体/门店及可信source status的merchantRef与`X-Merchant-No`一致；独立PAY另外检查真实typed支付成员。现有Order(0)通用BIZ转发链缺foundation，不直接借用。以专用精确安全链承接该路径，不混入内嵌PAY PortalGuard。两端未知结果沿原业务单恢复，host不存文件字节/密钥/审核状态。
- App保留env+typed身份+支付商户+requestNo草稿日志，未知查询并显式续原命令；账号/支付商户切换后丢弃迟到响应。入口放在已有真实支付来源与管理员能力可用时，独立页收渠道号和可选证明；不放N3表单，不保留HashKey输入或mock立即ACTIVE语义。

验证限于本次增量：PAY typed越权/跨merchant文件/幂等冲突/上传与提交未知恢复/运营关联错号与审批不绕过；host foundation与支付商户错配拒绝；App实际HTTP草稿、上传、提交、恢复及LINKED不变ready。无166部署或远端写入。

N2实施接线补充：V78已被另一会话的NewebPay运行时迁移占用，保留该改动，本批使用V79。新claim/命令/私有文件表属于isolated，沿EnvironmentSchema的execution_environment/execution_partition列、CHECK及自然唯一键分区；业务SQL不手写执行环境列，取Guard可信主体绑定。日志对精确claim路由整段省略请求body，含被拒绝的credential及multipart；新文件路由登记Guard精确multipart处理，避免落默认1MiB正文缓存。BIZ作者/回执始终含subjectType+subjectRef；OPS遵循实际view/manage和merchant数据范围。critical审计在同行事务检查成功（现有record空结果视失败回滚），不忽略其吞异常返回。关联账户时核对完整MID/subMID、market/merchant/channel及真实锁定版本，保留原审批与异人核验。

N1执行环境补充：source control/claim intent属于跨环境共享稳定归属（与source link、store projection同类），不拆成两套归属。金融历史在启用隔离时分别进入SANDBOX/PRODUCTION的REQUIRES_NEW只读短事务检查并合并事实；prepare父事务继续持有跨环境source/financial首次写门闩。不能在已绑定连接内切环境，也不能unwrap绕过隔离。任一环境历史或覆盖不可用都不得认领。

N1独立进销存边界：inventory.enabled或shop.inventory.enabled开启时，返回INVENTORY_HISTORY_UNKNOWN并阻止新认领。独立inventory有自己的DataSource/事务，不能把host“有事务”当作跨库原子锁；本批不擅自迁库存或以零余额放行。商品/旧店库存由CORE自有事实检查并参加host写锁；独立库存需另有owned完整事实与首次建业主/远端写入预留协议后再解除部署阻断。该限制不阻止原认领单向恢复，也不改变独立库存原业务流程。


### 2026-11-10 N1 商品事实遗漏修正

只读复核确认占位主体可在经营台保存商品草稿与设置库存；上架准入不等于建品门槛。认领不得留下仍归旧主体的商品、SKU、草稿或门店商品/库存/价格行：产品自有事实按明确tenant及源entity或认领store检查全部状态（含软删），存在即阻断，不在N1迁移产品数据。商品建品/改品、SKU及库存写事务参与既有归属共享门闩，保持entity→store顺序，与认领排他锁串行。

独立库存使用独立事务管理器/远端部署，host门闩不能证明其原子性；本批不修改库存领域或跨库迁移，库存启用而未具备完整归属协议的部署由host显式INVENTORY_HISTORY_UNKNOWN阻断。

N1投影修订补充：V647将mch_store.updated_at精度提升为DATETIME(3)（不改变非空/默认规则），本地改挂采用数据库CURRENT_TIMESTAMP(3)与原快照+1毫秒的较大值。避免用原秒精度时间强推未来一整秒后，紧接的正常门店编辑把修订时间写回较小值，导致最终投影被当作旧修订丢弃。源身份约束和PAY要求新修订严格大于expectedRevision保持不变。

N2号码展示：BIZ回执只返回商户号掩码；受现有OPS view/manage与数据范围保护的运营详情可读取完整渠道MID/subMID，供人工与既有账户逐字核对，不能据掩码匹配。渠道密钥在任何claim DTO/日志/证明说明字段中均不收集，继续只在既有PAY凭据中心登记。


### 2026-11-10 N1 门店经营配置边界补充

merchant自有范围/履约行仍持entity_no，仅改mch_store会导致迁后按主体读取丢失配置。本批不迁经营配置：任意状态/软删的本tenant本store mch_service_area或mch_fulfillment_channel，或源entity的mch_entity_community存在，即STORE_CONFIGURATION_HISTORY阻断。channel-area/pickup仅按店关联，不另加无依据门禁；不扩展独立库存迁移。MerchantStoreService与StoreFulfillmentService实际写事务参加entity→store归属共享门闩，不能依赖忽略updateById零行后的旁表写入获得原子拒绝。


N2 host接线偏差：旧15路由的BizOnboardingForwarder及Order(0)认证链保持原样，新增host专用claims forwarder及Order(-1)精确资源链；沿原HTTP超时/不重试/不重定向/原token和字节/私有响应头白名单策略，不构造第二支付authority、不转发X-Internal-*用户头。新链真实DB会话→BizContext+既有commerce admission→finance/FoundationPaymentAccess→可信source status的merchantRef与X-Merchant-No一致后才转发；NONE回409、映射错配403、依赖不可用503。新增开关shop.pay.channel-account-claims.enabled默认false，不建host影子BIND状态或私有文件。

### N2 原操作拒绝回执修订（2026-11-10，本地实施）

成功写接口仍返回View。两个原操作查询（GET `/channel-account-claims/by-request/{requestNo}`、GET `/{claimNo}/commands/{requestNo}`，OPS命令查询同样）统一返回`CommandReceipt {requestNo,outcome:SUCCEEDED|REJECTED,result?:View,failure?:{code,reasonCode,message}}`。普通详情/列表不变。客户端只在SUCCEEDED接收result；REJECTED明确结束原日志并显示记录的业务拒绝，修正后另开新意图。404只表示尚无回执，不能单凭404换键。

在实际身份/资源归属校验之后，业务单锁内持久化确定的版本、草稿状态、材料过期、账户坐标/市场不匹配等拒绝（原hash保留）；创建草稿的可变市场/目录拒绝也需保留原key的拒绝回执，命令表允许失败CREATE未有claim引用。相同key同hash重放固定成功/拒绝；不同hash仍冲突。确定拒绝记录不得保留部分业务写，采用局部验证或业务事务savepoint保持原单/文件原子性。身份/权限不可用、传输未知、SQL、Vault及critical审计失败不记成REJECTED，仍保原键查询/重放。

渠道准入复用实际MarketService启用市场及PayChannelMasterPort.enabledChannels(market)，不用全局channels.containsKey或DomainChannelProductCatalog对未知组合的兼容放行。测试目录可启用实际NEWEBPAY产品/本币限额以允许核实认领；这只表示目录可申请，不创建/核验/启用账户或门店ready。


### 2026-11-10 本批实现与本地验收结果

N1 写入/恢复已实现：真实 USR 登录及选店、短事务冻结、PAY 持久预留/确认/取消、本地提交后单向恢复、员工撤权和最终投影验收通过；[N1 证据与范围](../handoff/nearpay-biz-onboarding-2026-11-09/n1-local-acceptance-2026-11-10/README.md)。N2 已实现独立 PAY 业务单/私有证明/原操作回执、host 精确权限转发、App 冷恢复及 OPS 核实关联；真实双 JVM 重启、上传响应丢失与 OPS 成功/拒绝响应丢失旅程通过；[N2 证据、发布开关和剩余项](../handoff/nearpay-biz-onboarding-2026-11-09/n2-local-acceptance-2026-11-10/README.md)。

最终 LINKED 不代表实际账户已核验、启用或门店可收款；本次账户仍 UNVERIFIED/PENDING，门店绑定数零，host 没有新增影子 payment/qualification。新增开关默认关闭，App N2 依赖既有 BIZ 基础开关提供会话/成员；首批 UI 仅 NEWEBPAY。独立库存/商品/经营配置迁移及真实供应商收款仍按证据页剩余项推进。本地增量检查范围及已知 H2 限制如证据页，未宣称全量或生产验收。保留三仓现有未提交改动，无提交/推送，166 不部署。
