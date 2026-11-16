# 进件材料 OpenAI 识别

状态：实现及本地验证完成，主线提交与部署中。档位1。用户确认台湾公司登记、身份证正反面及银行证明，支持图片/PDF。

## §0 需求与验收

依据：[入驻进件建店需求](../../requirements/入驻进件建店-需求与四层对齐.md)的资料填写、人工审核流程及本次用户指令。

| 验收 | 实现 | 验证 |
|---|---|---|
| 原Qwen图片识别保留，配置选择OpenAI，无隐式服务商回退 | CertVisionPort、条件Gateway | providerSelection |
| 图片/PDF内联，密钥仅服务端，不存响应 | OpenAiCertVisionGateway、CertDocumentReader | imageAndPdfUseResponses、invalidDocumentsNeverReachProvider |
| 台湾登记、身份证、银行字段分别抽取，不猜值 | DocumentType白名单、严格JSON Schema | documentFieldsAreRestrictedToSelectedType |
| 运营确认回填，不自动保存或审核 | OPS接口、PAY运营材料面板 | permissionsAndTaiwanOnly、explicitSelectionDoesNotSave |
| 失败/拒绝/截断/未配置不冒充成功 | 原null/recognized=false约定 | unusableResponsesReturnNoRecognition |

## §1 接口与配置

- 保留 `POST /biz/qualifications/recognize` 和原Cert字段，补PDF输入；商品识别不切换服务商。
- 新增 `POST /ops/onboarding-documents/recognize`，multipart `file`、`documentType`；沿用 `merchant:admission:update` 或 `merchant:apply:onbehalf`；地区读取系统TW配置，不接受页面切换。返回 `recognized/documentType/fields/confidence`，`Cache-Control: no-store`。不生成附件凭证或审核事实。
- 类型REGISTRATION/OWNER_FRONT/OWNER_BACK/BANK_COVER，字段白名单分别限定。文件上限12MiB，图片沿原1600px/JPEG重编码清理EXIF，PDF复用已有PDFBox在内存验证，无公开存储。
- `shop.ai.vision.provider=compatible`默认或`openai`；OpenAI专用 `base-url`默认 https://api.openai.com/v1、`model`、`api-key`、`timeout-seconds`位于`shop.ai.vision.openai`前缀。
- 独立开关 `shop.ai.vision.openai.enabled`，未指定时兼容原`shop.ai.vision.enabled`。启用证照识别不需要改变原商品识别开关。
- 密钥由用户填写 `/Users/robin/.config/ai-shop/ume166/openai-vision.env` 的 `SHOP_AI_VISION_OPENAI_API_KEY`；通过真实测试后仅同步该项到UME166 `/data0/app/ai-shop/ai-shop.env`，不进Git/日志/浏览器。部署须真正重新加载运行容器env，不能只修改宿主文件。
- Responses API使用input_image/input_file、text.format严格Schema、store=false；不发送Qwen参数，不记录供应商原始错误/文件/识别号码/密钥。

## §2 实施与交接

1. 既有端口扩展默认不支持的文档能力；新增OpenAI实现，保留原Cert接口和服务商。
2. 共用文件准备与台湾OPS临时识别接口，保留原上传、资料保存、审查和渠道流程。
3. 独立PAY OPS识别面板经原authedFetcher发送真实运营Bearer到固定同源宿主路径 `/ops-shop/ops/onboarding-documents/recognize`。密钥从不进入PAY前端。
4. 所有勾选默认关闭，显式回填当前未保存values；保存继续原敏感字段seal/CAS。不自动上传附件、保存、送审或派发渠道。保存/提交/重载、草稿版本/主体/证件分支变化清除原文，离开时中止请求，不持久缓存。
5. 台湾身份证发证日用民国七位，出生/设立日用公历八位；非台湾身份证类型不自动回填身份证号/日期。只识别明示新台币实收资本额，资本总额/登记资本不能代替。银行号保留前导零；渠道字典值仍需人工选择。
6. 精确登记独立PAY OPS的POST接口消费者，不提高孤儿基线；固定路径扫描兼容主线已有环境模板前缀，补实际logout调用回归，不改登出业务。
7. PAY运营构建以线上主体材料分支9193a64a冻结源码叠加本批修改；保留V46/V47功能，PAY后端、H5、渠道配置不变。宿主增量发布保留其他已部署代码。

## §5 验证记录

- 宿主相关命令：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -o -f backend/pom.xml -pl shop-app -am -Dtest=OpenAiCertVisionGatewayTest,OpsOnboardingDocumentControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。适配器4项、实际方法鉴权/TW限制/PDF3项通过。独立开关补充同组配置回归。
- PAY新测试及原草稿/封存相关15项通过；typecheck、保留已部署功能的兼容live构建通过。跨服务消费者与模板路径扫描3项通过。
- 真实Java Gateway六项通过：纯合成公司登记、身份证正面、银行证明各PNG/PDF；标识和带前导零账号精确匹配。收窄资本语义后，追加包含注册资本999999/实收100000的公司登记PNG/PDF，均正确得到实收100000。
- 历史先遇401无效密钥，用户换新后认证200但429余额不足；用户充值后已通过真实识别。以上历史停点已解除，不用模拟结果代替真实识别。
- 密钥已同步并回读一致，其他env保留，未在同步时重启服务。UME166模型查询HTTP200。
- 独立审查的历法、资本额、原文生命周期3项已修正，复核无剩余阻断。发布、主线门禁及登录后浏览器验收待补充。

[脱敏证据](../../evidence/openai-onboarding-20261116/local-verification.json)。合成材料仅证明协议和抽取行为，不代表真实证件准确性已验收。

官方协议：[文件输入](https://developers.openai.com/api/docs/guides/file-inputs)、[结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)。
