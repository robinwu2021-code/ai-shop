# 经营实体证件识别（App 对接）

状态：已实现。near-pay 原文件读取接口已在 near-pay `feat/entity-material-recognition` 实现（2026-11-16，未部署）；部署前已上传文件识别在联调环境仍回依赖不可用。档位1。依据：App 合同 `ai-shop-bapp-entity/docs/api/经营实体证件识别接口.md`（经营实体资料全量方案 §14）及 [进件材料OpenAI识别](TDD-进件材料OpenAI识别.md)。

## §0 需求与验收

| 验收 | 实现 | 验证 |
|---|---|---|
| 新增经营实体前识别登记证明，只读不存 | `POST /biz/entity-recognitions` multipart | `BizEntityRecognitionControllerTest#beforeCreateReadsOnlyRegistration` |
| 已上传文件按 fileRef 识别，文件须属于该实体 | `POST /biz/entities/{e}/recognitions`，经 near-pay 内部读原文件 | `#uploadedFileIsReadThroughPayForOwner` |
| 8 种 docType 按 TW_ENTITY_2 字段键返回，取值按合同归一 | `EntityRecognitionType` + 端口新增文档类型 | `#valuesFollowAppContract`、`OpenAiCertVisionGatewayTest#entityTypesUseOwnFields` |
| 认不出 / 未配置 → `recognized=false`、`fields={}`（HTTP 200） | 沿用端口 null 约定 | `#unrecognizedIsEmptyNotError` |
| 不支持的 docType、fileRef 格式不对 → 10400；不属于该实体 → 10404 | 框架枚举绑定、fileRef 正则、near-pay 404 映射 | `#badReferenceIsBadRequest` |
| 账号级调用，不需要选店 | `@perm.merchantAccount()` + 路由 ACCOUNT 通道 | `CommerceRoutePolicy` 登记、`BizEndpointPermTest` |

## §1 接口与配置

- `POST /biz/entity-recognitions`：multipart `file`、`docType`、`requestNo`；`docType` 只收 `REGISTRATION`。
- `POST /biz/entities/{entity}/recognitions`：JSON `{requestNo, fileRef, docType}`；`fileRef` 形如 `file:entity-profile/<32位hex>`；先按 `MerchantEntityService.detail` 核本人主体。
- 返回 `{docType, recognized, fields, lowConfidence}`，`Cache-Control: no-store`。`confidence < 0.8` 时全部返回字段列入 `lowConfidence`。
- 实例地区须为 TW（同 OPS 识别）；服务商、密钥、开关沿用 `shop.ai.vision.*`，无新配置。控制器随 `shop.pay.entity-profiles.enabled` 启用。
- **near-pay 内部接口（已实现，见 near-pay TDD-支付服务-I4「实体资料原件读取与审核识别」）**：`GET /internal/pay/v2/entity-profiles/{entity}/files/{file}/content?sourceScope&actorRef`，返回原文件字节与 Content-Type；文件不属于该实体/来源回 404。ai-shop 侧 `PayEntityProfileApi#content` 已按此调用。

docType 与端口类型：

| App docType | 端口 DocumentType | 字段 |
|---|---|---|
| REGISTRATION | ENTITY_REGISTRATION | name taxNo legalForm registeredCity registeredArea registeredAddress incorporationDate capitalAmount representativeName businessDescription |
| CORPORATE_REGISTRATION | 同名 | corporateTaxNo corporateName corporateAddress corporateCapital representativeName |
| ID_CARD_FRONT | 同名 | representativeName identityNo birthDate issueDate issuePlace idIssueType |
| ARC_FRONT | 同名 | representativeName identityNo birthDate issueDate expiryDate |
| ARC_BACK | 同名 | arcBackNo |
| PASSPORT | 同名 | representativeName identityNo birthDate expiryDate |
| BENEFICIARY_ID_CARD_FRONT | 同名 | name nationality birthDate identityNo |
| BANK_COVER | BANK_COVER（与 OPS 共用） | bankCode branchCode accountName bankAccount |

归一：日期只留 `YYYY-MM-DD`；`capitalAmount`/`corporateCapital` 只留数字；`legalForm` 只留 `ENTERPRISE`/`INDIVIDUAL`；`idIssueType` 初發/補發/換發 → `FIRST`/`REPLACEMENT`/`RENEWAL`；`issuePlace` 去括号。不合规的值丢弃，不猜。
`industry` 是 profile-template 的选项值，证件上读不出，不返回，App 仍人工选择。OPS 的 `REGISTRATION` 地址语义不变，故 App 登记证明另用 `ENTITY_REGISTRATION`（地址拆分为县市/区/其余）。

## §2 实施

1. `CertVisionPort.DocumentType` 增加 7 个实体类型；`OpenAiCertVisionGateway` 提示词补字段定义。
2. `CertDocumentReader` 增加按字节读取的入口，multipart 与 near-pay 回读共用缩放/PDF 校验。
3. `PayEntityProfileApi#content`；新增 `BizEntityRecognitionController`（两个端点 + `EntityRecognitionType` 归一）。
4. `CommerceRoutePolicy` 账号通道登记两条路由；`BizEndpointPermTest` 登记权限表达式。
5. 外部消费者登记：`docs/api/entity-recognition.openapi.json` + `docs/api/backend-ui-delivery-entity-recognition-flutter.json`（App 在 ai-shop-bapp，不在本仓 b-app 扫描面内）。
6. App 侧无需改动：开 `ENTITY_WORKSPACE_OCR=true` 即对接；建立后识别须等 near-pay §1 接口上线。

## §5 验证记录

- 命令：`mvn -o -pl shop-app -am -Dtest=BizEntityRecognitionControllerTest,OpenAiCertVisionGatewayTest,OpsOnboardingDocumentControllerTest,StoreOpeningRoutePolicyTest,BizEndpointPermTest,ArchitectureTest test`（JDK 21）。
- 结果：BizEntityRecognitionControllerTest 5/5、OpenAiCertVisionGatewayTest 5/5（含新增 entityTypesUseOwnFields）、OpsOnboardingDocumentControllerTest 3/3、StoreOpeningRoutePolicyTest 1/1、BizEndpointPermTest 12/12 通过。
- ArchitectureTest 22/23：`domainsMustNotTouchWebRuntime` 失败于 `OpsPlatformController.logout(HttpServletRequest)`，来自对接分支已有提交 55e1b472a，非本次引入。
- 闸门：生成物 26/26 最新（含合并两分支后积压的 13 份重生成）；app-backend-orphan、app-contract、backend-ui-delivery、v2-docs、ops-orphan 通过。sql-portability（19 文件）与 shared-guards（728 断言 15 红，基线 12）在合并提交上已失败，非本次引入；本次补 README 索引后少 1 红。
- 未做：真实 OpenAI 调用（沿用 OPS 识别已验证的适配器，本次只加类型与提示词）；near-pay `files/{file}/content` 未实现前，已上传文件识别在联调环境会回依赖不可用错误，App 转手动。合成图片仅验证协议与归一，不代表真实证件准确性。
