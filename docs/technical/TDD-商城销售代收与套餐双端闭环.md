# TDD-商城销售代收与套餐双端闭环

状态：已发布台湾 166；正式目录及双端未付款订单回读通过。真实实收、付款成功和付费权益交付未验收；用户确认尚未收款。
创建：2026-11-13。档位：2（运营端、平台资金、商户 App 跨端契约）。
需求依据：[平台端功能清单：套餐销售代收](../requirements/平台端功能清单.md#套餐销售代收与双端闭环2026-11-13增补)、用户 2026-11-13 最后一项答复“恢复销售代收：销售收钱，运营确认到账”。
关联：[运营端套餐商城](./TDD-运营端套餐商城.md)、[上线开关与商品清单](./商城上线开关与商品清单.md)、[ADR-058](./ADR/ADR-058-商城销售代收复用平台资金与权益链路.md)。

运营先确认并发布套餐，运营或 App 为同一门店按同一 SKU 正式报价、下单；销售实际收钱后，运营确认实收，平台资金账更新付款事实，再交付门店权益。App 回读订单和订阅确认结果。

## §0 需求 → 设计

| AC | 可观察的验收标准 | 落点 |
|---|---|---|
| SC01 | 运营先回读等级、SKU、周期、币种、售价、有效划线价、权益、适用行业、版本和发布/在售状态；未发布/停售不可报价 | CatalogTab、ProductsTab、CatalogQueryService、正式报价 |
| SC02 | 运营为指定门店按已发布报价下单，买方为 STORE，发起人为真实运营，重试返回原单 | AssistedTab、CommerceOfflineOpsHttpController、DefaultMallFacade |
| SC03 | SALES_COLLECTION 不要求银行收款档案、银行账号或银行流水；旧 BANK_TRANSFER 原流程继续有效 | 支付域版本化收款条款、线下报价、readiness |
| SC04 | 运营记录真实销售人员、金额、币种、时间、收款凭据号、私有凭证及确认原因；确认身份取会话 | 平台员工目录适配、运营订单抽屉、核款服务与资金账 |
| SC05 | 订单创建/联系销售均不发权益；核款金额币种匹配且未作废才结算并交付，App 回读一致 | PlatformCollectionService、PaymentFacade、CommerceFulfillmentService、订阅读口 |
| SC06 | 同 actionNo 原参重试恢复；换 actionNo 仍不能重复使用同一销售凭据付另一单，权益仅交付一次 | 原操作记录、资金唯一键、原履约身份 |
| SC07 | App 保持全部报价单列表，选中的有效 SKU 接正式报价/订单；权限、停售、切店/退出清除草稿和隔离迟到响应 | plan_catalog_pricing、onboarding_entitlements_page、mall_offline_api、既有 confirmPurchase |
| SC08 | 先完成运营端套餐和运营购买验收，再完成 App 购买；每笔订单在两端对应相同门店/版本/金额/付款和权益状态 | 同环境同门店集成证据 |
| SC10 | 指定测试店可选 TEST 核款，原资金凭据标记 TEST，真实实收读口排除测试 | allowlist、ReviewCommand、PlatformOfflineLedger |
| SC11 | 销售附件可后补，资金关键字段不可覆盖，补资料原键幂等且不重复发权益 | receipt-documentation、mall_offline_action SUPPLEMENT、OPS 原件恢复 |
| SC12 | 166 运营正常登录，指定测试单核款后 App 回读同店付费权益 | 接管测试店投影原件、真实浏览器与模拟器5560 |
| SC09 | 金额不符、作废后实收、超时未知不伪装为付款成功；退款仍使用原退款审核与真实出款记录 | 既有 UNMATCHED/LATE、void/recovery、RefundFacade |

所有设计均落在上述 AC；没有独立销售佣金、销售缴款结算或新商城的需求。

## §1 现状与影响面

- `ops-web/app/mall/catalog-tab.tsx` 已支持行业/三档方案发布；`products-tab.tsx` 管 SKU 售价、划线价和版本。
- `assisted-tab.tsx` 已支持运营代客报价/下单/发银行付款指引；`order-drawer.tsx` 支持核款、凭证和三维订单状态。
- `DefaultOfflinePurchaseFacade` 使用支付域银行档案取条款。ReviewCommand 强制 bankReference；ShopMallOfflineCollectionAdapter 仅接受 OFFLINE/BANK_TRANSFER；PlatformCollectionServiceImpl 和 PlatformOfflineLedger 也硬约束银行方式。因此不能仅换界面文案，或伪造银行档案给销售代收使用。
- 订单、付款、权益交付和恢复已有正式实现；复用现有平台资金账及真实授权，不直改订阅、不新增通用 Outbox 或第二套购买状态机。
- 当前 `ShopMallConfiguration` 将 OfflineCollectionPort 装配到 PlatformCollectionService，含 embedded pay 装配；目标环境实施时先确认实际 Bean/进程，复用实际客户端，不假设改独立 near-pay 同名类就能上线。
- Flutter `ai-shop-bapp` 新方案列表是只读选择；既有商城已有正式报价、升级报价、订单、银行指引和付款申报。复用其订单/恢复能力，按收款方式分支展示。
- 共享工作区已有大量他人未提交修改。本方案只登记本任务落点，实施时逐文件读取最新差异，不覆盖或提交其他任务代码。

## §2 方案与实施顺序

### 2.1 采用方式与范围

采用在既有线下订单中新增 `OFFLINE / SALES_COLLECTION`。银行转账继续为 `OFFLINE / BANK_TRANSFER`，两种方式分别冻结条款和凭据身份。订单、资金账、数字权益、退款审核及恢复沿原实现。

用户已批准恢复销售代收及本方案；以下为已采用的业务细化：
- 销售人员先复用同租户启用的运营员工记录（staffNo），具备代购职责且在目标门店授权范围内；不新建销售人员主数据。核款人由当前会话确定，销售收款人可与核款人不同。现有岗位权限决定可否兼任，不在本稿额外强制双人审批。
- 核款表示运营确认销售代平台收款已发生；不另设“销售再缴到平台后才生效”的第二资金审批。销售佣金、个人借款、现金盘点与缴款对账另行处理。
- 首轮以已确认的台湾零售纯数字 Plan 为验收主线；硬件/组合套餐保持原报价和履约合同，不因本轮改写库存/物流。

### 2.2 业务顺序

| 阶段 | 操作与结果 |
|---|---|
| A 运营确认套餐 | 回读版本和发布状态；核对权益与有效周期、币种精度和原价/售价。历史已确认值为 PRO 月199/年1990，CHAIN 月599/年5990 TWD；以运营实际审核发布结果为准，不自动用原型99/990替换配置 |
| B 运营配置销售代收 | 为平台/币种启用销售代收条款，配置付款期限和联系说明，无银行字段；停用只影响新报价，不使旧真实收款失去核对入口 |
| C 运营购买 | 选择门店、在售 SKU、销售代收，获取服务端冻结报价后下单；展示应付金额与付款期限，准备原付款尝试 |
| D 销售收钱、运营核款 | 选真实收款员工，记录收款凭据号、实收金额/币种、秒精度收款时间、私有凭证和原因，明确确认操作 |
| E 资金及交付 | 支付域记录真实钱款并裁决 MATCHED/LATE/UNMATCHED；匹配款经原付款刷新/交付链路写订单和权益；交付处理中继续回读 |
| F App 确认及购买 | 先核对 A 的同一报价列表、再核对 C–E 的运营订单及权益；随后从 App 发起销售代收购买，运营按 D 核款，App 再次确认结果 |

销售代收订单在 App 显示“联系销售付款／等待平台确认”，不显示银行账号、转账备注或银行凭证申报表。商户不能在 App 自行核款。未付款保持 FREE/原权益；只有订单付款与权益交付均达到已完成才显示“已开通”。零元订单沿内部结算，不要求销售收钱。

### 2.3 契约增量

以下合同已随实现同步到 OpenAPI；旧银行请求/快照保留原有语义。

| 合同 | 增量及约束 |
|---|---|
| 线下管理档案/条款 | 增加 `collectionMethod` 区分 BANK_TRANSFER/SALES_COLLECTION；销售档案含平台收款主体、币种、付款期限、说明和启用状态；不用银行账号作为销售收款身份。旧无该字段的持久快照按其既有银行语义读取 |
| `/ops/biz-mall/offline/profile` | 增加按方式读写；银行保存继续原银行字段，销售保存禁止银行占位值；管理仍用原档案读写权限；readiness 分别按实际启用方式返回 |
| 运营及商户 `/offline/quotes`、升级报价 | 可明确选择已启用收款方式；缺省保留原银行行为。服务端获取可信平台条款并冻结方式；后续不能在订单上切换方式；升级仍走既有抵扣/版本规则 |
| 订单/报价/指引读模型 | 返回冻结的收款方式；准备销售付款尝试时返回销售说明和原订单，不生成 transferRemark。已存在的指引路径继续负责幂等准备支付，页面按方式命名 |
| `/ops/biz-mall/offline/orders/{no}/confirmations` | 销售方式新增 `collectorNo`、`collectionReference`；保留 actionNo、actualAmount、actualPaidAt、evidenceKey、reason。bankReference 仅银行方式必填。actualReceiver 仍表示平台收款主体，不能填写销售姓名或随客户端增权 |
| 受限员工候选查询 | 以代购/核款职责返回同租户且在目标店范围内的有效收款员工最小字段（staffNo/displayName）；复用现有员工主数据；服务端确认再次授权。不让业务操作员为选销售而额外获得完整 IAM 管理权限 |
| 商户订单读模型 | 可展示销售姓名快照和收款方式；不暴露运营私有凭证、内部原因或其他员工资料；已付信息仍取服务端 |
| 内部 Port/支付 DTO | 沿现有 OfflineCollectionPort、PlatformCollectionService 显式传方法、销售和凭据号；不把销售凭据塞入 bankReference 或伪造 provider 交易号 |

权限复用 `commerce:catalog:edit/publish`、`commerce:collection:profile:read/write`、`commerce:mall:assisted:purchase`、`commerce:mall:offline:evidence/confirm`、订单/订阅读权。App 购买沿 `commerce:mall:purchase`；没有职责不能购买/核款。新词条走 OPS 现有三语和 Flutter 四语词条及生成流程。

### 2.4 持久化、资金身份和恢复

- 已发布商品、报价、订单、条款快照、付款尝试和已付事实冻结；不能把旧银行订单读成销售代收。增量迁移编号在实施前查最新号，仅添加新迁移，不改已应用 SQL。
- 复用 `stl_payment` 原资金记录和离线操作原件，销售核款原始字段保留于不可变 proof；新增通用收款凭据键列/唯一索引，旧银行专用唯一键及其算法保持原样。
- 销售收款唯一身份为服务端规范化后的 `(tenantNo, SALES_COLLECTION, collectorNo, collectionReference)`，用于防同一真实凭据跨订单/换 actionNo 重复核款；该身份不含币种、金额、订单或档案版本，避免改参数绕过。不同销售的真实独立凭据不合并；凭据号去首尾空白、保留大小写，精确二进制摘要。
- 若一笔真实凭据覆盖多订单，本轮不拆分自动归集：先由运营按实际业务出具可追溯的单订单收款凭据；金额不符保持 UNMATCHED，不挪入另一单或强行开通。
- 金额/币种不匹配继续写真实异常款而不付款成功；作废后款继续按原晚到/退款处置，不自动恢复原单；付款截止仍由冻结条款和服务端裁决，不由 App 延期。
- 私有凭证沿原票据预留、上传和读取范围；不可跨订单/跨操作员/跨用途借用。原 actionNo 加原内容摘要恢复，响应未知不换号重做。
- 幂等唯一规范引用 [幂等设计规范](./reference/幂等设计规范.md)，这里只登记本动作落点：

| 参数 | 数据库 | 服务端 |
|---|---|---|
| quoteNo + idempotencyKey + 原门店/买方/initiatedBy | 原 mall_order/事件认领 | 原 DefaultMallFacade 下单/原键查回，不改锁序 |
| actionNo + 冻结方式 + collectorNo/reference + 实收/凭证 | 原 offline action + stl_payment 新销售凭据唯一身份；资金/回执同支付事务 | 原 ACCEPTED → 支付确认 → 付款刷新 → 完成/恢复，不新增 Outbox |
| 原 paymentAttemptNo/orderNo/组件来源 | 原付款/交付/权益唯一约束 | 原 PaymentFacade、CommerceFulfillmentService；重复核款/恢复不重复发权益 |

### 2.5 模块与执行批次

| 顺序 | 模块/路径 | 结果 |
|---|---|---|
| 1 | `ops-web/app/mall/catalog-tab.tsx`、`products-tab.tsx`、既有商品种子/只读回读 | 核对真实套餐资料，列差异；发布是可审阅的实际版本操作，非原型样例灌入 |
| 2 | `backend/commerce/commerce-api` offline/port 合同、`commerce-core` offline/mall/fulfillment、`commerce-spring-boot-starter` 既有 HTTP/配置/鉴权 | 销售条款、报价、付款方式冻结、核款和恢复；沿原包名/类名扩展 |
| 3 | `backend/pay/pay-domain` PlatformCollectionService/PlatformOfflineLedger、宿主 ShopMall*Adapter/Configuration、实际客户端装配、增量迁移 | 同一平台资金账记录销售实收及唯一凭据，原银行行为兼容 |
| 4 | `ops-web/lib/types/commerce.ts`、contracts/https、`app/mall/collection-tab.tsx`、`assisted-tab.tsx`、`order-drawer.tsx` 和词条 | 运营可配置销售代收、购买、录入实收、查询付款/交付；mock 不作为实收证据 |
| 5 | `/Users/robin/work/ai/ai-shop-bapp/apps/merchant_app/lib/features/standard_onboarding/{onboarding_entitlements_page,mall_offline_api}.dart`、`features/mall/mall_pages.dart`、词条 | 方案 SKU 接既有确认购买，按销售方式展示，回读运营订单和权益；没有月/年切换 |
| 6 | OpenAPI/接口文档/数据库字典及两仓相关测试 | 统一生成/对账，验收记录实际门店/版本/订单/支付/权益来源及真实操作人 |

只读核对可先执行；真实发布、部署和真实核款分别用实际配置/记录审阅。真实收款验收需要实际销售收款及真实私有凭证，本地测试数据只证明测试链路，不冒充实收。

### 2.7 后续六节点设计、review 与实施计划（2026-11-13）

本节按用户批准顺序执行。上轮实现已完成；以下从 Task 7 编号，避免与历史六项实现混淆。每个节点分别记录设计结论、实施结果和证据，依赖外部事实的节点保留待验收状态，其余节点继续。

**全局约束**：沿既有订单/账/权益；不造真实款、不直改订阅；不因验收重发目录或停售其他商品；仅操作自有测试店/订单和模拟器 5560。共享生产发布前后比对当前制品，保留并行任务变更。源码、制品、环境快照分开标注，缺失来源不得推断。复用未改代码的有效测试，仅补受影响检查。App 正式包不使用 debug 密钥，不生成替代生产密钥，不主动安装不同签名覆盖当前调试包。

#### Task 7: 节点一 / 运营页面

- Design review：资源包含字符串不能证明页面可用。使用实际 166 公网 OPS、正常运营身份与真实接口，查看正式目录、销售档案、代购报价以及当前 App 年付订单核款表单。浏览器测试不 mock、不注入伪造权限。
- Interfaces/files：既有 `/ops/auth/login`、`/ops/biz-mall/**`；`ops-web/app/mall/{collection-tab,assisted-tab,order-drawer}.tsx`；证据归档 `docs/handoff/sales-collection-166-2026-11-13/closure-review/`。
- Steps：正常登录→打开各页→选择目标店并获取报价（不再创建订单）→打开指定年付单与确认表单（不提交核款）→截图并记录请求结果、脚本错误和真实字段。
- Verification：运行 task-owned Playwright 脚本。Expected：正式 TWD 199/1990/599/5990 四价在目录，销售说明和候选员工可读，表单使用真实币种/销售/凭据字段，不出现银行占位，不产生 confirmations 请求。若发现行为缺陷先补受影响测试后修复。

#### Task 8: 节点二 / 真实实收闭环

- Design review：必须有真实收钱事实才能确认。用户已答“尚未收款”，本轮只核对待付款、实收为空、FREE；不将预备工作记作真实闭环通过。
- Interfaces：年付单、冻结 TWD 1990/365 天条款；既有私有 PLATFORM_RECEIPT 凭证、confirmations/actionNo、cash-receipts、payment、subscription 与 App 原单。
- Steps：核对固定订单与收款期限；真实收款后由销售提供员工、金额/币种、时间、凭据号和私有凭证→冻结确认请求/actionNo→正常核款→原键恢复未知响应→核对资金 SUCCESS、付款成功、交付完成、唯一付费权益来源→App 同店回读。逾期不新编收款时间，按服务端晚到裁决处置。
- Verification：本轮只读 readback。Expected：未付款、不发权益；真实收款发生后另补该订单的完整证据。当前期限前保留订单，归档时不提前关闭待验订单。SC08 的运营代购实收路径也仍待验：旧月付单已作废，不恢复它；待用户确有运营路径购买/实收需求后另按正式报价下单，以独立真实凭据验收第二条路径。当前不再为测试额外制造待付订单。

#### Task 9: 节点三 / 异常与恢复

- Design review：生产不制造异额款或假凭证。以原生 MySQL9、OPS 恢复测试和 App 状态测试证明写入/重试，以 166 原关闭单+未付单只读证明部署状态。
- Interfaces/files：PlatformOfflineLedger、SalesCollectionWorkflowMysqlTest、运营 mall-offline-review 测试、Flutter 订单状态专项；既有测试日志和 166 订单读口。
- Steps：核对既有证据覆盖同凭据跨单/并发、原 actionNo 原参恢复、异额/异币种、关闭后款、银行兼容；对照已部署类；仅缺失或代码有改动时补测。
- Verification：逐项证据矩阵。Expected：资金写入不重复、异常不伪装成功、关闭单不提示再次付款；本地验证与目标环境只读结果明确区分。

#### Task 10: 节点四 / 版本与回退基线

- Design review：当前 SHOP/OPS 有并行发布，不能把旧源码号视为全部当前代码。冻结当前运行 SHA、容器、迁移与静态路径，核对本任务的类/销售页面仍存在。无完整来源时写“混合制品/来源待补”，禁止谎称可全量源码复建。
- Interfaces/files：线上 jar、OPS current symlink、健康检查、Flyway V666、任务旧制品比对清单；归档 baseline.json 和 rollback.md。
- Steps：锁外只读采集→比对 task entries→记录实际环境/制品来源→记录同一时点回退备份与校验。恢复代码必须同时兼容后续迁移/并行业务，不能简单回滚到销售上线前 jar；不回滚资金数据、不撤已用迁移。
- Verification：元数据和健康只读检查。Expected：实际 SHA/路径/时间可复查；本任务类未丢失；回退边界与缺失来源明确，未为了 VERSION 文件重发旧静态资源。

#### Task 11: 节点五 / 正式 App 发布准备

- Design review：当前 166 为 debug 包；build.gradle 未接正式签名，脚本只验 applicationId，不能称正式发布。补齐外部签名配置、预期证书 SHA256 和产物校验，构建计划不包含密码。保留现有 SSE 仅 166 开关政策，不把 SSE 发布扩为本轮隐含需求。
- Interfaces/files：Bapp `tooling/build_brand.py`、新增独立签名校验模块及相关 tests、`apps/merchant_app/android/app/build.gradle.kts`、`branding/README.md`；正式入口 main_prod，nearboss applicationId 不变，API 明确指定 166 shop-api。
- Steps：先补缺签名/错证书/伪 debug 正式包拒绝的用例→接环境变量外部 keystore 路径/alias/密码与预期证书→正式构建前检查→apksigner 验签及证书一致性、aapt package/版本核验→清单记录 SHA/证书指纹/源码版本，不记录秘密。现有通用 CI 无正式签名，不声称自动生产发布可用。
- Verification：Python 构建/签名契约 tests；真实 release 缺资料时必须在构建前明确失败，debug 流程不受影响。Expected：仅正式证书匹配的 APK 可导出为 production；无 key 时节点保持待签名，不生成替代 key、不覆盖 5560 的 debug 安装或账号数据。

#### Task 12: 节点六 / 测试归档

- Design review：财务记录不删除。归档记录门店、订单、作废动作、SKU 停售、当前权益、截图及尚未完成项；仍用于核款的年付单保留。
- Interfaces/files：两仓发布记录与本 TDD，节点1–5 JSON/图片/命令结果。
- Steps：汇总节点结论→清理仅本任务临时进程/令牌文件→保留可复查证据与未完成原因；真实闭环完成后正常关闭剩余自有未付测试单（需先确认无款），不删除门店历史和账。
- Verification：链接/JSON/敏感内容人工核对，git diff。Expected：每节点有通过/待办和准确证据范围，不把“已部署/测试通过”当“真实收款通过”。

**Review focus**：线上 UI 与 mock 证据混淆；真实付款时间和 late 路径；并行发布版本误判；生产签名绕过/密码泄漏/证书不匹配；不同签名安装导致数据丢失；测试归档提前关闭待验订单。权限、资金唯一键、原银行快照及迁移 V666 保持原合同。

### 2.8 测试核款与确认后补资料（已由用户授权执行）

用户最新要求：当前为测试阶段，可以确认收款；确认需要填写资料，确认后仍可补充。用户已批准实施；只对配置允许的测试门店执行明确标记的测试确认。

- 范围：仅指定测试店/订单明确标记 TEST，复用现有订单→平台资金→付款→权益→App 回读链路。测试记录必须能识别、真实资金报表不能混计；若当前读模型/报表无法正确识别测试记录，应先补此能力再核款。本轮结论为测试闭环，仍不表示真实到账。
- 配置与核款拆开：平台收款主体/方式/期限/联系说明在收款配置维护，订单核款只显示冻结快照；门店、订单、套餐、应付金额不手填。核款需确认销售人员、实收金额/币种、时间和唯一凭据号；测试凭据可由服务端生成 TEST 前缀，不伪称真实银行流水。
- 资料可后补：附件、补充说明和联系资料可先空，展示“资料待补”；核款成功后订单详情保留“补充收款资料”。对既有原始必填 evidenceKey 的调整必须落实后端契约与恢复测试，不能仅由前端跳过。
- 原事实不覆盖：付款金额/币种、收款人员、时间、唯一凭据号及订单关系确认后锁定；后补资料追加留痕，记录操作人/时间/原核款 actionNo；仅影响资料完整度，不重复调用 confirmations、不重新付款、不重复发权益。资金事实纠错走已有对账/退款流程。
- 两个状态独立展示：“付款状态”和“资料状态（待补/已完整）”；App 只回读付款/权益及必要销售说明，不暴露私有凭证与内部备注。
- 实施顺序：恢复运营登录→测试标记和报表隔离→核款最小必填/后补契约→订单详情补充入口→重复补充/权限/原键恢复专项→指定年付单测试确认→App 回读 PRO/365天及唯一权益来源→归档证据。
- 实现契约：ReviewCommand/HTTP 增加 testPayment（旧请求缺省 false）；`commerce.mall.offline.test-stores` 默认空，显式 STORE allowlist 限制测试核款。测试仅 SALES_COLLECTION 且凭据 TEST 前缀；测试/真实标记冻结在原 action 与支付 proof.mode=TEST 中，不可后改。
- 销售 evidenceKey 可空，银行仍必填；空值由服务端生成 DOCUMENTATION_PENDING:<actionNo> 作为非附件引用，私有附件验证只对实际提供的 assetKey 执行。资金关键字段、唯一凭据及旧幂等摘要不改。
- 后补复用 mall_offline_action：新增 SUPPLEMENT 类型及 supplement snapshot；以 orderNo+actionNo 作为原件键，引用已有 APPLIED/UNMATCHED CONFIRM actionNo，保存 note/evidenceKeys/操作人/时间；相同原参重试返回原件，换内容冲突。GET receipt-documentation 返回确认原件和追加记录，POST receipt-documentation 写入；读沿订单权限，写沿核款权限并验证附件属于本人/本店/本单/PLATFORM_RECEIPT。商户不暴露此内部接口。
- 正式资金读口 cash-receipts 排除 proof.mode=TEST，测试资金不进入真实异常款退款入口；订单付款查询/原履约仍回读 TEST 的 SUCCESS，用于本次权益验收。当前平台套餐无独立收入统计界面；归档提供正式/测试分组 SQL，禁止 raw SUCCESS 合计作为真实到账收入。现有商户消费者支付报表按 pay_order 范围，不纳入本平台套餐单。
- 恢复登录：既有 Outbox 停机 inspect/adopt 单条指定测试店事件，不删除记录、不禁用启动校验；补具名门店同步发布适配 unified 接收路径并测试稳定原件号/时间/重试，避免下次重启再次失败。
- 实施与验证：先恢复并检查真实公网登录；再集中修改 facade/API/JDBC/HTTP/OPS 文案及契约，测试覆盖无附件核款→补附件→原键重试→不重复权益、测试门店越权、跨单附件、旧银行；最后增量打包，按当前线上 jar/OPS 基线仅合入任务变更并验证，执行测试年付单核款和 App 回读。
- 当前发现：2026-11-13 真实公网浏览器登录 POST /ops-shop/ops/auth/login 返回 502；静态页200且无页面脚本错误。运营交互验收未通过，不以本地 mock 代替。

## §3 取舍

| 方案 | 取舍 | 状态 |
|---|---|---|
| 在现有平台线下链路新增销售代收方式 | 资金、订单、交付、幂等恢复一致，需修改显式银行依赖和两端合同 | 已采用 |
| 另建销售订单/独立资金账再同步订阅 | 多一套对账和恢复边界，与用户要求两端同单不符 | 不采用 |
| 将销售收款填入银行转账字段 | 无法区分真实银行流水和销售凭据，审计与去重身份失真 | 不采用 |

## §4 边界和待核对

- 本轮恢复销售代收，覆盖此前 2026-11-06“暂缓”及 11-11 清单“不随本清单恢复”的范围限制；保留历史记录；当前发布状态以文首与 §5 为准。
- 默认复用平台员工为销售、核款即确认代平台实收、单凭据单订单。这三项已由用户确认采用。
- 不新增佣金、销售资金缴款台账、在线支付供应商、跨店共享权益；不改变门店向消费者收款/渠道进件链路。
- 实施者可决定内部 DTO/辅助方法的最小划分、精确迁移编号和测试组织，但不能自行更改以上资金身份、付款时点或权益交付语义。
- 实施/目标环境验证时先回读套餐发布和平台收款装配；当前正式目录与开关已回读；收款事实须另行核对。

## §5 实现 → 需求及当前证据

| AC | 已验证 | 尚需验证 |
|---|---|---|
| SC01/SC02 | 166 正式零售四 SKU；历史四测试 SKU 已停售；运营正式报价并代购成功 | 当前生产 OPS 浏览器交互 |
| SC03 | 销售/银行档案隔离、冻结条款；166 已启用销售代收 | 当前生产表单切换及只读检查 |
| SC04/SC05 | 本地 MySQL9 SalesCollectionWorkflow、金额/币种不符；166 待付款保持 FREE | 真实实收、SUCCESS、交付与 App 付费权益回读 |
| SC06 | 本地原生 MySQL9 同凭据跨单/并发、原键恢复及权益不重复 | 复核证据与发布代码对应关系 |
| SC07 | Flutter 72 项通过；166 正常 App 正式列表/下单/订单回读 | 正式签名发布后的同账号回读 |
| SC08 | 运营月付单在 App 可见；App 年付单在运营可见 | 两条路径真实实收后的闭环 |
| SC09 | 原银行、晚到/退款、异币种、CLOSED 及运营恢复专项通过 | 目标环境无资金副作用的恢复核对 |

本地 Java 集成后 73 项相关测试、OPS 23 项及 typecheck/build、Flutter 72 项及 analyze 通过；未改代码复用既有证据，不相加重复测试次数。MySQL9 为 3308。发布、截图与逐项 JSON 见 [166 验收归档](../handoff/sales-collection-166-2026-11-13/README.md)。

## §6 实现与历史记录

资金身份、冻结方式、并发唯一键、员工范围、商户权限及旧银行兼容均已实现。历史本地“未部署”状态已被 166 发布结果取代；V665 已被其他业务使用，本任务实际迁移为 **V666__platform_sales_collection.sql**，不修改旧迁移、不 repair 历史。

首次独立设计评审无重要未决问题；代码评审发现的迟到报价、空档案切换、实际币种、App 冻结方式及 CLOSED 状态均已修复。销售新凭据并发不再对缺失唯一键作 FOR UPDATE，以原生唯一索引裁决；相关并发测试三次通过。完整历史以发布归档和提交为准。

166 使用 SHOP 本地 PlatformCollectionService / 主库 stl_payment；不能因 standalone 配置而改独立 near-pay。当前测试店 ST261113Y693VWBMKT：运营月付单 b1d96a07a7494dfaba2f3816f298996d 已按真实未付款状态作废；App 年付单 01fc24ae8f2a40779243fe6b0ec40654（TWD 1990）待付款，初始权益 FREE。两单未录入实收。用户确认“尚未收款”。
