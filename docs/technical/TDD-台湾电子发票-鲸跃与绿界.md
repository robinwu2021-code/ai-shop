# TDD · 台湾电子发票接入：鲸跃与绿界

状态：草稿 · 2026-11-21 · 档位 2。交付范围为接口、架构、数据库、配置、凭证和联调设计。本文归档在 `ai-shop-codex`。本版设计以 `ai-neargo` 的 trade T3 为实施基线。

## 0. 需求与验收映射

| 需求 | 设计落点 | 验收证据 |
|---|---|---|
| 核清 UME 鲸跃接口、签名、环境和凭证 | §1 源码与 Apollo 核验 | 用沙箱商户执行获取营业人资料、取号、开立、查单和逆向操作，核对原始状态码 |
| 对照绿界官方契约 | §2 API 和字段映射 | 用官方测试向量校验加解密，按测试商户执行开立、查询、作废、折让、打印 |
| NearGo 支持两家并可扩充 | §3 trade T3 厂商适配与 POS | 切换门店后验证新单走新厂商，历史发票继续走原厂商 |
| NearGo 保存发票密钥 | §4 业务库和 §5 Secret Manager | 两家测试账号独立授权，测试／正式隔离，密钥轮换和审计可核对 |
| 可部署与联调 | §5 配置和 §6 验收 | 端点、账套、字轨、样张、异常恢复及数据回查均有记录 |

## 1. 鲸跃 · UME 核验结果

### 1.1 环境与来源

核验来源为 UME Git 备份 `trendlab-pay/service@8e4f861bc` 的 `invoice-web`／`invoice-rpc` 及 UME Apollo 数据库。以下是 2026-11-21 每个集群最新未废弃的发布记录，值按原样记录。

| Apollo `invoice/<cluster>/application` | 发布 ID | `api.cetustek.url` | `invoice.get.number.server` | Web 账号配置 |
|---|---:|---|---:|---|
| `default` | 1305 | `http://ve.ei.com.tw/InvoiceEntitySync/InvoiceAPI?wsdl` | `0`，走厂商取号 | 本次发布无该组字段 |
| `stage` | 297 | `http://ve.ei.com.tw/InvoiceEntitySync/InvoiceAPI?wsdl` | `0`，走厂商取号 | 本次发布无该组字段 |
| `dev` | 447 | `http://invoice.cetustek.com.tw/InvoiceEntitySync/InvoiceAPI?wsdl` | `1`，控制器返回固定号段 | 有 |
| `uat` | 1241 | `http://invoice.cetustek.com.tw/InvoiceEntitySync/InvoiceAPI?wsdl` | `0`，调用配置中的厂商地址取号 | 有 |

`RequestURL.CETUSTEK_URL` 读取上述属性。旧 helper 对此 URL 直接 POST，包含配置中的 `?wsdl`。`default` 是 UME 默认部署基线，`uat` 是 UME 测试集群标签。**Apollo 集群名称无法证明厂商账户或字轨属于沙箱。** 两个旧地址改用 HTTPS 后访问 WSDL 返回 403。需由鲸跃书面确认新系统每个账号可用的 HTTPS 测试／正式 **POST 地址**、WSDL、白名单和字轨归属。NearGo 正式环境待确认后才填地址和启用。

鲸跃公开的 HTTPS `InvoiceMultiWeb/InvoiceAPI` WSDL 可读取，包含 `CreateInvoiceV3`、`QueryInvoiceByOrderid`、`CancelInvoice`、`CreateAllowance`、`CancelAllowance` 等。UME 生产链路使用另一个 `InvoiceEntitySync` 服务，首批按该旧合同行为做适配，端点由厂商确认。 [官方服务页](https://www.ei.com.tw/InvoiceMultiWeb/InvoiceAPI) · [公开 WSDL](https://www.ei.com.tw/InvoiceMultiWeb/InvoiceAPI?wsdl)

### 1.2 UME 到鲸跃的 REST 上游与 SOAP 下游

UME 上游入口为 `POST /invoice/{operation}`，JSON DTO 定义在 `invoice-rpc/.../model/Pos*Entity.java`。鲸跃下游统一为 SOAP 1.1 POST，`Content-Type: text/xml;charset=UTF-8`。Envelope 命名空间 `http://schemas.xmlsoap.org/soap/envelope/`，操作命名空间 `http://webservice.cetustek.com/`，源码中前缀为 `pm`。Helper 使用空 SOAP Header、XML body 内三字段认证，未设置独立 `SOAPAction`。返回解析 SOAP `return` 内容，有的操作回 XML，有的操作回状态码。原契约证据为 `CetustekInvoiceServiceHelper.java`、`CetustekXmlDataUtil.java`、`Soap*.java` 与 `CetustekInvoiceServiceImpl.java`。

以下每个 SOAP 操作都带共同字段 `officeid`、`posno`、`posid`。字段大小写按备份中的 XML alias。

| 动作 | UME 入口 | 鲸跃 SOAP 操作 | 除认证外的字段与回执 |
|---|---|---|---|
| 登录／营业人资料 | `login`、`getOfficeInfo` | `QueryOfficeInfo` | 无额外字段。解析 `Office` 的 `ID`、`TopID`、`Name`、地址及 `QRCode` 等，验证账号和账套。登录成功后 UME 发自己的 `invoiceToken`。 |
| 捐赠码 | `getLoveCode` | `QueryLoveCode` | 无额外字段。载具类型另由 UME `getVehType` 提供。 |
| 申请字轨 | `getInvoiceNumber` | `ApplyInvoiceNumber` | `year` 为民国年，`month` 为双月期，如 `11-12`。成功回申请号。 |
| 领号及检查 | `getInvoiceNumber`、`checkInvoiceNumber` | `TakeInvoiceNumber`、`CheckInvoiceNumber` | `applyid`。领号后保存申请号、期别、字轨、起讫号和适用设备。 |
| 开立上传 | `uploadInvoice` | `UploadInvoice` | `invoicexml` 内为 `Invoice`，UME 将内层 XML 作为转义文本传送。源码识别 `S0` 成功。 |
| 查询状态 | `queryInvoiceStatus` | `QueryInvoiceStatus` | `invoicenumber`、`invoicedate`。超时恢复应先按已分配的原发票号和日期查。 |
| 发票逆向 | `cancelInvoice`、`voidInvoice` | `UploadCancelInvoice`、`UploadVoidInvoice` | `invoicexml` 带原发票号、日期、买方识别及该动作的原因字段。分别识别 `C0`、`V0`。业务名称与撤销语义按厂商批准的版本验收。 |
| 折让、取消折让 | `uploadAllowance`、`cancelAllowance` | `UploadAllowance`、`UploadCancelAllowance` | `allowancexml` 带折让号码、日期、原票引用及折让金额／品项，取消沿用原折让身份。分别识别 `A0`、`C0`。 |

`UploadInvoice` 内层关键字段：

| 字段组 | XML 参数 |
|---|---|
| 发票与原单 | `InvoiceNumber`、`InvoiceDate`、`InvoiceTime`、`OrderId`、`OrderDate`、`InvoiceType`、`RandomNumber` |
| 买方与交付 | `BuyerIdentifier`、`BuyerName`、`BuyerEmailAddress`、`MailSend`、`CarrierType`、`CarrierId1`、`CarrierId2`、`DonateMark`、`NPOBAN` |
| 金额 | `TaxType`、`TaxRate`、`SaleAmount`、`FreeTaxSalesAmount`、`ZeroTaxSalesAmount`、`TaxAmount`、`TotalAmount`、`PayWay` |
| 明细 | `Details/ProductItem` 含 `ProductionCode`、`Description`、`Quantity`、`Unit`、`UnitPrice`、`Amount`、`DType` |

保留原始状态码及分类，`invalid`、`error`、`exist`、`nodata`、`notake` 按对应操作判断。取号成功、厂商收件、财税平台上报及 POS 出纸分别记录。厂商字段长度、可空性、回复时限和 `S1/S2` 的语义须以受理本商户的新版接口文件及测试回执确认。

### 1.3 签名、账号与密钥位置

| 用途 | UME 核验结果 | NearGo 处理 |
|---|---|---|
| 鲸跃 SOAP 身份认证 | `officeid ← officeId ← invoice.t_user.OFFICE_ID`，`posno ← userName ← ID`，`posid ← passWord ← PW`。读取 `UserMapper.xml` 和 SOAP 模型确认。所核对实现使用 XML 凭证，无额外厂商请求摘要。 | 按厂商账号保存：`officeId` 为账号资料，`posNo`、`posId` 存在 NearGo 专用 Secret；正式只经确认的 HTTPS。 |
| UME POS 登录签名 | `SHA256(userName + "," + passWord + "," + braId + "," + deviceId + "," + officeId + "," + braSecret)`。登录请求的 `token` 对照此值。 | 仅旧 POS 相容网关需要此契约。NearGo 原生端沿交易与门店身份认证。 |
| UME POS 后续签名 | `SHA256(invoiceToken + "," + randomStr + "," + braSecret)`。见 `InvoiceIndexController` 与 `InvoiceTokenServiceImpl`。服务端按 `braId` 通过门店 RPC 读取 `msm_mms.store_info.BRA_SECRET`。 | 门店签名密钥与鲸跃账号分独立 Secret。若以后接旧 POS，再用占位密钥样本和旧 SDK 核对摘要的编码及十六进制格式。 |
| UME 工作阶段 token | 鲸跃账户登录成功时由 UME 产生并保存至 `invoice.t_user.TOKEN_STR`。源代码以账号、设备和随机串生成，供后续请求关联。 | 承接旧 POS 时重新签发对应会话。第三方适配器只持自己的厂商凭证。 |
| UME Web 默认凭证 | `InvoiceServiceHelper.java` 从 Apollo 读 `web.invoice.username`、`web.invoice.password`、`web.invoice.officeid`、`web.invoice.braid`、`web.invoice.deviceid`、`web.invoice.platform`。仅 dev、uat 的本次发布含整组键。 | 每个环境及每个营业人各自配置授权账号。 |
| 证明联 QR 参数 | 鲸跃 `QueryOfficeInfo` 返回 `Office.QRCode`。umePOS 取该资料用于发票 QR 生成。 | 若需本地生成，另存独立的 QR Secret，先用鲸跃确认的样张验证。已生成的条码内容随原发票冻结。 |

UME 现有文件包含记录签名原串及 SOAP body 的日志语句。NearGo 适配器的日志只记请求号、厂商码、业务状态与耗时，凭证、签名原串及完整发票载荷不输出。此次核验使用字段名和存在性，未读取或复制商户密钥值。

## 2. 绿界 · 官方 B2C MIG 4.0 接口

测试基址 `https://einvoice-stage.ecpay.com.tw`，正式基址 `https://einvoice.ecpay.com.tw`。首批接 B2C 开立及本规格支持的统一编号场景。独立 B2B 交换采用另外的授权与契约。测试凭证从官方测试介接资料取得，正式从签约商户发票后台取得。 [MIG 4.0 开立](https://developers.ecpay.com.tw/49890/) · [测试介接资讯](https://developers.ecpay.com.tw/7398/) · [B2B 交换](https://developers.ecpay.com.tw/14808/)

### 2.1 认证与成功判断

外层 JSON 是 `MerchantID`、`RqHeader.Timestamp`（发送时 Unix 秒）、`Data`。内层业务 JSON 带同一特店编号，按绿界规则 URL Encode，再用发票 `HashKey`／`HashIV` 做 AES-128-CBC / PKCS7，加密结果 Base64 放入 `Data`。响应先检查 `TransCode`，解开 `Data` 后按 `RtnCode` 判业务结果。绿界发票接口使用这组加密契约。应使用官方向量固定编码、字节与换行行为，支付渠道的验签逻辑不参与发票接口。 [官方参数加密说明](https://developers.ecpay.com.tw/10205/) · [MIG 4.0 查询](https://developers.ecpay.com.tw/49903/)

### 2.2 两家动作映射

| NearGo 动作 | 鲸跃 POS SOAP | 绿界 B2C API 与关键参数 |
|---|---|---|
| 开立 | `UploadInvoice`，需已配号 | `/B2CInvoice/Issue`；`RelateNumber`、`SalesAmount`、`TaxType`、`Items`、买方与交付字段。保留返回 `InvoiceNo`、日期、随机码。 [开立](https://developers.ecpay.com.tw/49890/) |
| 查单与恢复 | `QueryInvoiceStatus`，原号和日期 | `/B2CInvoice/GetIssue`；`RelateNumber`，或 `InvoiceNo` 与 `InvoiceDate`。 [查询](https://developers.ecpay.com.tw/49903/) |
| 作废 | 鲸跃的 `UploadCancelInvoice` 与 `UploadVoidInvoice` 动作按厂商语义分别映射 | `/B2CInvoice/Invalid`；原 `InvoiceNo`、`InvoiceDate`、`Reason`。 [作废](https://developers.ecpay.com.tw/7906/) |
| 折让 | `UploadAllowance` | `/B2CInvoice/Allowance`；原票、折让金额和品项。按选定的纸本或线上流程分别对账。 [一般折让](https://developers.ecpay.com.tw/49894/) · [线上折让](https://developers.ecpay.com.tw/49897/) |
| 查／作废折让 | `UploadCancelAllowance` 作废，结果查法待供应商确认 | `/B2CInvoice/GetAllowanceList`、`/B2CInvoice/AllowanceInvalid`；记录并沿用绿界折让号。 [查询](https://developers.ecpay.com.tw/7928/) · [作废](https://developers.ecpay.com.tw/7911/) |
| 载具校验 | UME 本地与 `getVehType` | 手机条码 `/B2CInvoice/CheckBarcode`；其他载具按商户启用版本映射。 [手机条码](https://developers.ecpay.com.tw/7886/) |
| 证明联打印 | 原号、随机码、条码参数及 POS 排版 | 以 `GetIssue` 核对原件。按接口条件调用 `/B2CInvoice/InvoicePrint`，返回的 `InvoiceHtml` 是有效期一小时的列印网址。长期保存原件和打印数据，有需要时再取网址。 [打印](https://developers.ecpay.com.tw/7949/) |
| 离线配号与后送（后续） | `ApplyInvoiceNumber`、`TakeInvoiceNumber`、`CheckInvoiceNumber` | 按设备业务模式选取号区间或带随机码及加密资料的清单，再用 `/B2CInvoice/OfflineIssue` 上传。 [区间](https://developers.ecpay.com.tw/13795/) · [清单](https://developers.ecpay.com.tw/15502/) · [上传](https://developers.ecpay.com.tw/13823/) |

### 2.3 开立字段决策

| NearGo 数据 | 鲸跃 | 绿界 |
|---|---|---|
| 原交易及本次开立身份 | `OrderId`、`OrderDate` | 商户内唯一 `RelateNumber`，最长 50 字符，生成仅用大写字母与数字，同一次开立重用。原业务单号另存。 |
| 发票号 | 预配 `InvoiceNumber`，记录 `applyid` | 在线由绿界分配。 |
| 买方资料 | `BuyerIdentifier`、`BuyerName`、`BuyerEmailAddress` | `CustomerIdentifier`、`CustomerName`、`CustomerEmail`，按所选场景校验必填。 |
| 纸本、载具、捐赠 | `CarrierType`／`CarrierId1`／`CarrierId2`、`DonateMark`／`NPOBAN` | `Print`、`CarrierType`／`CarrierNum`、`Donation`／`LoveCode`；各厂商枚举分别映射。 |
| 税与品项 | 应税、免税、零税率、税额、合计及 `ProductItem` | `TaxType`、`SalesAmount`、`Items[].ItemName/ItemCount/ItemWord/ItemPrice/ItemTaxType/ItemAmount`，并按官方规则传入场景所需的零税率原因等字段。 |

金额和税额采用 trade 保存的最终交易事实。保留完整金额精度，适配器按厂商契约映射必填字段与舍入政策。

## 3. NearGo 架构与生命周期

| 模块 | 设计职责 |
|---|---|
| `merchant` | 营业人与门店主数据、门店归属及管理员授权仍由 merchant 管理。 |
| `trade` T3 台湾 Region Pack | 消费 Model C 成交／退款事实，保存发票和反向操作原件，路由门店开票配置，供近场 POS 查询。现有 UAE `EInvoiceGateway` 是 PINT AE 上报端口。台湾增加适合开立、查单及折让的窄接口，复用 trade 的成交、税额、发票原件。 |
| `Cetustek`、`Ecpay` 适配器 | 在 trade 进程内调用厂商。按开立尝试的原账套读取 Secret，转换传输与响应。鲸跃 SOAP 按所签协议固定，绿界启用 B2C。 |
| `fnb` 打印与 Android 终端 | 用发票的冻结证明联数据进入现有 `print_job` 与 Terminal `PrintQueue/PrinterTransport`。验证 N5 内建打印适配及区网印表机。 |

路由顺序为 `区域和环境 → 法定营业人 → 授权门店 → 生效中的唯一厂商账户`。发现多条生效配置时由后台拒绝启用，已有开票原件沿原路由重试。先保存稳定的业务命令、厂商单号与预配发票号（鲸跃需要），随后在本地事务外发送厂商请求。超时标记 `UNKNOWN`，用原库内条件更新认领待查询记录，查询确认后才推进或允许原导入请求重送。封闭厂商切换期间的并发窗口由门店单一有效绑定和同一开票业务身份裁决。`UNKNOWN` 的发票不得切换厂商重新开立。

状态应能分别回答开票、财税上报、出纸。开票命令 `PENDING / UNKNOWN / SUCCEEDED / REJECTED`。发票原件 `ISSUED / VOIDED`，折让各有自身操作状态。财税上报使用厂商可证明的状态，无法取得时填 `UNKNOWN`。POS 索引按原发票号，打印状态沿现有 `print_job`，设备只确认发送时记录送印。首次打印失败与已出纸补印使用原发票与条码。首期建议服务端确认开立后才可打印证明联，离线暂存和先印后送需专项启用。历史厂商迁移要保留原开票资料和原账套引用。

## 4. 数据库设计

沿 `ai-neargo/docs/technical/db-naming.md` 的 NearGo 库表命名约定使用 trade `ng_trade`、`tax_` 前缀。当前 `tax_invoice` 是通用税票原件，`einvoice_log` 保存 PINT AE 上报，台湾拟新增专用厂商资料。以下为迁移评审的逻辑模式，尚未执行 DDL。业务表继承既有 BaseEntity 的租户、区域和版本约定。唯一范围内不得使用可空列。

| 表 | 主字段与建议约束 |
|---|---|
| `tax_invoice`（复用） | 沿原结构保存税票业务号、成交 ID、类型、税额、总额、币种等。原件身份 `tax_invoice_no` 保持唯一。 |
| `tax_tw_provider_account` | `id, tenant_id, region_id, seller_entity_id, seller_ubn, provider_code, environment, provider_account_code, office_id, secret_ref, status`。唯一 `tenant_id + region_id + environment + seller_entity_id + provider_code + provider_account_code`。厂商账户编码与运行环境属于稳定绑定。 |
| `tax_tw_provider_route` | `id, tenant_id, region_id, environment, seller_entity_id, store_id, account_id, effective_from, status`。每个路由范围仅一条启用记录，切换事务按范围串行。历史版本保留原 account。 |
| `tax_tw_invoice` | `tax_invoice_no, source_txn_id, issuance_scope, tenant_id, region_id, seller_entity_id, store_id, account_id, provider_code, environment, provider_order_no, provider_invoice_no, provider_invoice_date, random_code, delivery_kind, print_snapshot_ref, issue_status, filing_status`。唯一 `tenant + region + seller + source_txn_id + issuance_scope`，该业务身份不含厂商。厂商订单号在厂商账户内唯一。鲸跃首期预配的发票号按环境、卖方统编、期别及字轨号码唯一。 |
| `tax_tw_invoice_operation` | `id, invoice_id, action, operation_key, payload_hash, provider_request_ref, provider_result_code, provider_allowance_no, state, next_query_at, last_attempt_at`。唯一 `invoice_id + action + operation_key`。原操作、回执、税票状态同本地事务提交。按结果待确认与下次查询时间索引。 |
| `tax_tw_number_range`（鲸跃） | `account_id, apply_id, period, prefix, start_no, end_no, next_no, allocation_owner, status`。保留 `account_id + apply_id + prefix + start_no` 唯一。以号段行锁分配下一号码并在同事务登记发票或预配的唯一占号，防并发抢号。后续离线按设备分段分配。 |

数据库仅保存密钥引用、版本元数据及业务 ID。证明联快照按项目既有敏感字段加密及访问授权划分，文档或条码引用可供打印域读取。业务操作记录负责查询恢复，无需为同一恢复链路再建消息表。

## 5. NearGo 环境配置与密钥入库

**密钥归属 NearGo 项目。** 以 `ai-neargo/deploy/k8s/addons/external-secrets/` 的 Secret Manager／External Secrets 路径为部署基线，trade 只获所属区域及环境的读取权限。这是拟配置的运行时路径。项目 Git 收录配置结构和 Secret 引用，实际凭证由 NearGo 受控密钥库保存。 部署基线与 AWS 示例分别见 `ai-neargo/deploy/k8s/addons/README.md` 和 `ai-neargo/deploy/k8s/addons/external-secrets/aws.yaml`。

| 配置 | Sandbox | Production |
|---|---|---|
| trade 配置前缀（拟） | `neargo.trade.einvoice.tw`，`environment: SANDBOX` | 同前缀，`environment: PRODUCTION` |
| 鲸跃端点 | `NEARGO_TW_CETUSTEK_SANDBOX_URL`，待厂商确认对应 HTTPS POST 地址 | `NEARGO_TW_CETUSTEK_PROD_URL`，待厂商确认对应 HTTPS POST 地址 |
| 绿界端点 | `https://einvoice-stage.ecpay.com.tw` | `https://einvoice.ecpay.com.tw` |
| Secret 键路径（拟） | `neargo/tw/sandbox/einvoice/<seller>/<provider>/<account>` | `neargo/tw/production/einvoice/<seller>/<provider>/<account>` |
| 鲸跃 Secret 内容 | `posNo`、`posId`，需生成条码时另设专用 QR Secret | 同结构，使用厂商核发生产账套 |
| 绿界 Secret 内容 | `hashKey`、`hashIv`，账户表存 `MerchantID` | 同结构，使用正式发票商户凭证 |

建议以云 Secret Manager 作为同一项目内的保管点。trade 使用部署身份按 `secret_ref` 取商户凭证。若首批商户固定且部署只支持 External Secrets，可将单一明确版本映射进 trade 的只读 Secret volume，业务表引用对应挂载别名。上线流程需核对账号的营业人、区域、环境、字轨和证照后才启用门店路由。轮换新凭证时保持原厂商账户关联，历史发票继续用同账套的有效凭证查询与逆向。停用某商户的旧账户前先明确其历史票保管与操作方式。

实际入库所需资料为首批鲸跃门店及绑定的 UME 账套、厂商确认的测试／正式 HTTPS POST 地址、绿界测试／正式商户账号，以及目标 NearGo Secret Manager 项目与命名空间。本次完成位置核对与入库设计，未迁移 UME 凭证或写入运行时 Secret。

入库顺序为先确定营业人、门店和环境，然后通过受控身份将选定账套凭证直接写入 Secret Manager，最后登记 `account_id → secret_ref`。入库回执仅登记账套标识、Secret 版本和操作人。测试环境验证通过后，再为同一营业人开户配置正式路由。

## 6. 联调、切换与交付证据

| 步骤 | 鲸跃 | 绿界 | 通过条件 |
|---|---|---|---|
| 1. 建测试路由 | 由厂商确认 `InvoiceEntitySync` 的 HTTPS POST／WSDL，给测试账套与测试字轨。以 `QueryOfficeInfo` 核对营业人资料。 | 领取发票测试凭证与字轨，放入 NearGo Sandbox Secret，使用官方示例做字节级加解密测试。 | 查询回执的营业人和配置一致，环境交叉加载被拒绝。 |
| 2. 首笔开立 | 申请并领用测试号段，保存 `applyid`，用原号上传并查状态。 | 使用 `Issue`，按同一 `RelateNumber` 调用 `GetIssue`。 | 两边都保存原发票号、税额、品项、厂商回执，和测试后台一致。 |
| 3. 交付与打印 | 纸本、载具、捐赠、买方统编，验证鲸跃条码参数及样张。 | 同四类场景，验证 `GetIssue`／`InvoicePrint` 的适用条件及列印资料。 | N5 或区网打印首印，确认实际出纸与扫码。重试或补印保持原号。 |
| 4. 反向操作 | 按确认的操作语义测试作废、部分折让及取消折让。 | 测试 `Invalid`、`Allowance`、`GetAllowanceList`、`AllowanceInvalid`。 | 原发票及折让号可追溯，重复请求返回既有结果。 |
| 5. 超时与跨厂商切换 | 模拟厂商收件后客户端超时，按原号查单。 | 按原 `RelateNumber` 查单。 | 重复提交、进程重启、号段并发分配和跨双月期均无重复开票。门店切换后旧单沿原厂商。 |
| 6. 正式启用 | 厂商书面确认生产账套、地址、字轨、授权及首票安排。 | 核对正式特店凭据、适配版本、字轨及商户授权。 | 商户批准后给单店启用，按交易—原件—厂商回执—上报—列印证据核对。暂停新开时原厂商查询及逆向仍可用。 |

本文的可验证成果是源码与 Apollo 的对应关系、绿界官方接口对照、NearGo 库表和密钥引用设计。沙箱开立、厂商正式 HTTPS 连接、实际密钥迁入及实机打印尚未运行。实施后应把测试商户、发票业务引用、响应分类和实印样张登记到验收记录。
