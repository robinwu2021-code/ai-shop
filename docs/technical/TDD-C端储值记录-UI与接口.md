# C端储值记录 · UI与接口设计

状态：设计完成，待开发；不是已发布接口。日期：2026-10-21。
原型：[9屏交互稿](../../prototypes/consumer-stored-value.html)。

## §0 需求与界面对账

| 验收要求 | 原型 | API |
|---|---|---|
| 我的统一入口，无需先选门店 | s01、s02 | stores |
| 有储值记录的门店，最近一次储值或核销倒序 | s02 | stores |
| 储值与核销时间倒序混排 | s03 | records |
| 全部／储值／核销三个Tab，独立时间弹框 | s04、s05 | records.type、startDate、endDate |
| 不展示本金赠金拆分、独立核销码及线上退款 | 全部 | 使用消费者白名单DTO |
| 无记录、无筛选结果、失败重试 | s06、s07 | 空页／标准错误 |

本次是原型和契约设计，不实现新Controller或发布业务功能。接口统一遵循现有C端 `/mp` 路由，取代前文示意的 `/c`。既有会员码继续负责到店身份识别，储值页不签发单独核销码。

## §1 代码核对与复用

此前“后端只有商家查询”的判断不准确：Pay的 `StoredValueQueryService.stores` 已通过 `sv_user_store_index` 按本人及最近活动时间返回门店；`consumptions` 已查询已提交储值核销。已有 MpStoredValueController 暴露 /mp/stored-value/stores 和 /mp/stored-value/consumptions；前次review漏查了公开Controller。需要扩展目录展示合同，消费查询仍不能替代包含储值的混合账单。

| 模块 | 已有能力 | 待补项 |
|---|---|---|
| Pay储值 | StoreQuery、StoreItem、游标编解码；SvStatement及商家混合记录；消费者消费查询 | 消费者本人混合账单查询；白名单DTO；批量短号投影；必要索引核查 |
| Core主应用 | /mp鉴权、消费者身份、Pay本地／远程适配模式 | 消费者储值AppService及Controller；可信租户／用户上下文；日期与时区解析 |
| Pay内部HTTP | InternalStoredValueEndpoint.stores、服务Token鉴权 | 新增本人混合账单内部只读请求及客户端定义，不复用商家权限接口 |
| C端 | 我的、会员码、分页与UI组件、端点表／HTTP／mock体系 | 两个新页面、入口、类型契约、三语文案、空态与重试 |

身份只取自登录态；tenantNo使用已有可信租户上下文，不能让客户端指定或固定成MAIN。线上账户可能先由商家创建为customerNo，后绑定userNo；需核对既有绑定流程同步账户、索引和账单的情况。消费者查询以已核实的userNo归属为准，不用手机号模糊匹配、不用userNo/customerNo同字符串猜关联。

## §2 页面、模块及开发顺序

1. 核对已有消费者门店查询及身份绑定；确定门店时区与市场来源。
2. Pay新增消费者混合账单能力及内部合同；只查已提交事实。
3. Core AppService通过本地／远程Pay适配接入，Controller只接登录身份和查询参数。
4. C端从“我的→储值记录”打开门店列表，再打开单店收支记录。
5. 更新端点表、HTTP和mock、OpenAPI类型与三语文案，登记UI目录。
6. 定向验证身份隔离、时间边界、游标与展示后，再部署后端和C端H5。

建议页面路由：`/pages/stored-value/index`、`/pages/stored-value-records/index?storeNo=...`。门店名称以服务端响应为准。页内不放第二个门店筛选器；返回列表选择其他门店。后台变更账户不应导致历史消费记录消失，余额为零也保留门店。

## §3 对外API契约（待开发）

共用：登录必需；标准ApiResponse包裹；下面示例仅为data部分。币种遵循ISO 4217，金额为整数最小货币单位，不固定除以100；时间为UTC ISO 8601。游标不透明并绑定消费者、租户、门店与筛选参数；改变筛选须清空cursor。

### 3.1 GET /mp/stored-value/stores

参数：`cursor?: string`，`size?: integer=20`（1～50）。不接userNo、customerNo、tenantNo、entityNo。

```json
{
  "records": [{
    "storeNo": "STORE_EXAMPLE",
    "storeName": "悦颜美肤 · 科技园店",
    "lastOccurredAt": "2026-10-21T06:30:00Z",
    "lastType": "REDEMPTION"
  }],
  "nextCursor": null,
  "hasMore": false
}
```

门店来源：已有 `has_recharge=1` 索引，即至少有过一次实际到账储值；不是办理中、取消或收款未到账。该口径与已确认“有储值记录的门店”一致；即使余额清零、门店停业，仍可查询历史记录。若历史迁移存在仅核销且has_recharge=0的数据，应先核查来源并单独制定修复，不默默扩大目录口径。

排序：last_activity_at DESC、store_no ASC，复用已有游标规则。内部CONSUMPTION对外统一转换为REDEMPTION，避免两套核销枚举。展示文案由客户端基于类型和时间生成，无需自由文本摘要字段。不返回账户余额和本金赠金结构。

### 3.2 GET /mp/stored-value/store/{storeNo}/records

| 参数 | 约束 |
|---|---|
| type | ALL（默认）／RECHARGE／REDEMPTION |
| startDate | 可选YYYY-MM-DD，门店当地自然日，包含当天 |
| endDate | 可选YYYY-MM-DD，包含当天；服务端转换为下一自然日零点的排他上界 |
| cursor | 可选不透明游标 |
| size | 默认20，1～50 |

不同时提供Instant和LocalDate两套筛选；不使用设备时区解释日期。Core根据门店市场取得businessTimeZone，将自然日转为[from,to)后交Pay查询；不直接给起点加固定24小时，避免夏令时边界错误。单边日期允许；起点晚于终点返回400。响应头部回传已应用的日期和门店时区，打开弹框使用当前已生效条件。

```json
{
  "store": {"storeNo":"STORE_EXAMPLE","storeName":"悦颜美肤 · 科技园店","businessTimeZone":"Asia/Shanghai"},
  "filter": {"type":"ALL","startDate":null,"endDate":null},
  "records": [
    {"type":"REDEMPTION","title":"面部护理","currency":"CNY","signedAmountMinor":-12800,"occurredAt":"2026-10-21T06:30:00Z","documentNo":"EU20261021000001"},
    {"type":"RECHARGE","title":"日常储值","currency":"CNY","signedAmountMinor":13000,"occurredAt":"2026-10-20T08:20:00Z","documentNo":"PR20261020000001"}
  ],
  "nextCursor":null,
  "hasMore":false
}
```

储值金额为实际到账合计（如充100送30，显示+130）；核销金额为实际余额扣减。金额不能描述为“实付”。只包含已到账储值和已完成扣减，不展示草稿／待收款／处理中状态。排序occurred_at DESC、原始record_no DESC；后者只用于内部稳定排序，不返回内部身份。

消费者只读DTO不透出accountNo、customerNo、内部recordNo／businessNo、渠道凭据、操作人、paidAmountMinor／bonusAmountMinor。documentNo可空；历史无映射不补号、不回退显示长内部号。首期账单行不进入单据详情，避免引入额外入口和长号展示。需要明细时另行确认契约。

## §4 权限、分页及状态

- Core先取已登录用户，再构造可信服务请求；Pay再次按tenant+本人关联过滤，并校验目标门店有本人记录。请求不同会员的数据或不属于本人的门店统一404，避免泄露存在性。
- Pay通过本人账户及可信身份绑定关联查询SvStatement，不调用需要BizContext／商家权限的目录接口。
- 游标包含筛选摘要并签名；拒绝跨用户、门店、类型和时间复用。无法验证游标返回400；记录新增后既有游标继续向后，不提供不可靠total。
- 初次加载用现有loading；翻页失败保留已有记录并允许重试。切Tab／日期时取消或丢弃旧请求结果，防止慢响应覆盖新筛选。
- 时间弹框取消不应用；重置清空弹框草稿，确定后重新加载第一页并保留类型；清除筛选恢复ALL和不限时间。筛选已生效时“时间”按钮显示选中状态，日期摘要不占主要账单空间。
- 401走已有登录并返回原页面；403遵循平台禁用约定；404展示不可访问并允许返回列表；5xx提供重试，不显示为空列表。空门店列表和筛选为空用不同文案。

## §5 验收计划

设计检查：原型锚点、登记与生成目录一致，无独立样式块；每个主要控件对应API参数或已有导航。
开发后定向验证：本人/他人隔离、绑定前后身份一致、零余额及停业门店可查、未到账不入目录、跨日同时间排序与连续分页、三类型过滤、单边日期/非法区间/夏令时、旧空短号、快速切换筛选、首屏及翻页失败重试。未经验证不声称这些用例已通过。

## §6 交付对账

本次产出：consumer-stored-value.html九屏、registry登记、原型总览与UI目录生成物、本接口设计文档。仅设计与静态原型交付，业务代码、线上端点和部署仍待后续实施。

## §7 2026-10-21 原型规范修订

当前原型扩展为9屏。s01～s07对应查询流程与状态；s08为微信通知内容示意，s09为主动授权入口。

遵循现行生成规范《规范-字体》《规范-版面》《规范-组件》及C端base.css密度，而非已归档的B端十二条数值。C端375pt画面在原型按0.8缩放：页面边距14pt、卡片16pt、块间距10pt；内容常规字重、标题600、金额700并保持LTR等宽数字。类型使用sh-tabs对应的胶囊色块选中形态；白块按面色分层，无分隔描边。原型样式统一位于proto.css的consumer-proto作用域，产品落地使用sh-scaffold/sh-card/sh-tabs/sh-sheet/sh-empty/sh-btn，不能复制原型尺寸到业务页。

修正项：链接颜色不再落浏览器默认色；原生button重置并用胶囊按钮；日期输入采用同一字段样式；时间弹框覆盖遮罩并贴底，取消不改变已应用筛选；储值和核销Tab分别显示相应示例，避免点击储值却显示核销。原型仍是评审示意，不执行微信授权或资金操作。

## §8 按原型Review后端（代码静态核对结果）

结论：后端可复用基础查询和通知设施，但不能直接进入仅前端对接阶段；必须补消费者混合账单、通知场景及微信渠道接线。

| 原型/需求 | 代码证据 | 结论与修改 |
|---|---|---|
| s02门店目录 | StoredValueQueryServiceImpl.stores；SvUserStoreIndexMapper.firstDirectoryPage/directoryPage | 已有本人目录、has_recharge=1及活动时间排序；保留零余额目录。已有Core公开适配；需兼容扩展展示字段、核对离线顾客绑定后的索引归属与同时间游标 |
| s03混合记录 | StoredValueQueryService.consumptions仅核销；StoredValueMerchantQueryServiceImpl.records依赖CatalogScope商家范围 | 缺少消费者混合记录合同。Pay新增本人账户范围查询，不能让Core伪造商家权限或直接查Pay Mapper |
| s04类型 | SvStatement.type已有RECHARGE/REDEMPTION；StoreActivityType使用CONSUMPTION | 公开API统一REDEMPTION；分别转换内部目录与账单枚举 |
| s05时间 | 商家记录端支持Instant和LocalDate，按市场时区转换 | 新C接口仅收自然日；Core解析门店时区，Pay执行排他上界；游标绑定日期和类型 |
| 短号展示 | PR/EU/SV映射及商家账单numbered投影 | 新C DTO仅暴露documentNo，分页后批量投影；无映射留空，不返回内部号和双池 |
| s08核销通知 | EntitlementNotificationSubscriber已消费ENTITLEMENT_USED，messages.push生成站内消息 | 已有核销站内通知，不等于微信通知已实现；现在push关联参数为空，缺可鉴权账单跳转 |
| s09微信通道 | WxSubscribeSender、WxSubscribeGateway及POST /mp/message/subscribe | 可复用发送、授权和日志体系；未发现专用储值核销模板/发送方法。新增场景及模板映射，不复用到货模板凑字段 |
| 可靠性 | 已有Pay权益事件和Core OutboxConsumer | 核对sv扣账、usage及事件同事务；通知场景独立消费/任务幂等，不调用扣账写命令进行补发 |

以上为源码review，不代表环境模板可用或真实微信发送已经验收。

### 8.1 通知跳转需要的接口补充

混合账单GET接口增加可选 `focusDocumentNo`，只用于通知定位，不改变type/date/cursor语义。规则：与cursor互斥；首次带focus时限定当前消费者及门店，在同一排序下返回包含该笔记录的窗口及向后nextCursor；普通打开默认最近记录。focus属于旧记录且无documentNo时，站内消息可只跳该店最新账单，不补历史号。未知号或他人号统一404，不全局查单。被定位记录仅做轻量高亮，不增加独立详情页。

通知目标页面：`/pages/stored-value-records/index?storeNo=...&focusDocumentNo=...`；微信page参数按小程序页面格式去掉开头斜杠，并使用已登记路径。客户端不能把page参数当任意跳转URL；登录后恢复参数，再走服务端本人校验。

新增 `GET /mp/stored-value/notification-capability`（待开发）：登录必需，返回 `{channel:"WXSUB", available:boolean, templateId:string|null, reasonCode:string|null}`。只有匹配当前小程序appid的已启用场景和真实模板才available=true；不返回secret/openid或虚构可用模板。H5/App不展示原生微信授权按钮，仍可从消息中心查结果。

授权复用 `POST /mp/message/subscribe`，在核对现有templateIds/accepted合同后调用；客户端记录授权结果不等于平台承诺长期订阅。若微信返回逐模板接受/拒绝，客户端逐模板记录，服务端只接受已配置模板，不接受任意模板ID扩大发送范围。不新增“手动发送消费通知”C端接口。

## §9 核销后微信通知方案

首期微信渠道按仓库已有“小程序订阅消息”实现。公众号模板消息属于另一种身份与通道，当前不混用。商家App核销只是触发消费事实，不能替消费者在商家App中完成微信授权。

### 9.1 触发和模块归属

1. **Pay储值**：核销实际提交完成，扣账、消费记录及可恢复事件同事务落库；报价、预占、取消、失败和重复提交不新发成功事件。
2. **Core事件桥接**：接收ENTITLEMENT_USED并校验身份；只将assetKind=STORED_VALUE路由到拟新增的STORED_VALUE_REDEEMED通知场景，卡券核销仍走自身场景。
3. **Core通知中心**：读取权威消费快照，生成站内消息和微信待发任务。按tenant+usageNo+recipient+scene确定业务幂等键，eventNo用于投递去重；避免同一核销产生不同eventNo而重复通知。任务持久化成功后才能确认消费完成。
4. **shop-notify微信网关**：按真实模板、对应appid的openid和有效授权发送；复用通知日志与通道设施。不得把unionid、手机号、模拟openid当成微信接收人。
5. **C端**：在记录页的“消费通知”入口由消费者主动请求订阅授权；通知打开后走登录及本人权限查询，定位指定门店账单记录。

### 9.2 内容和事实来源

最小通知内容：门店、消费项目/摘要、实际核销金额（正数消费额）、业务发生时间和“查看记录”。这些是业务字段，不预设thing1/amount2等真实微信关键字编号；由后台批准模板的类型/长度进行映射校验。超长项目按模板限制安全截断，完整内容在账单查看。

首期不在微信通知展示余额，可避免异步读取最新余额冒充该笔消费后的余额；若未来需要，必须使用该笔sv_statement.balance_after_minor快照。币种来自账单，不从门店当前配置猜测。消息内容不含本金赠金、原始凭据、会员码、安全令牌和内部账户号。

现有EntitlementUsed缺少币种、门店名称、标题及账后快照，因此事件不能直接拼完整消息。优先新增Pay只读消费通知事实查询（tenant+usageNo），返回已提交消费的user/store/title/currency/amount/occurredAt/documentNo白名单；Core以服务认证调用并验证事件归属。禁止Core直接JOIN Pay表。若决定扩展事件，必须新增版本、兼容旧事件并同步codec，不能悄悄改变schemaVersion=1。

### 9.3 失败、授权和运维

| 情况 | 处理 |
|---|---|
| 有真实模板、openid及可用授权 | 排队发送；落供应商结果 |
| 未绑定C用户、无openid、无授权额度 | 标记不可发送原因；有C用户则保留站内结果；不把历史通知在日后授权时集中补发 |
| 模板未配置/字段错误 | 标记配置失败并进入受控告警；不循环重试，不标记已送达 |
| 可判定的临时失败 | 有上限退避重试，只重试通知任务，不重放扣款 |
| 请求已发但结果未知 | 标记UNKNOWN并保留审计，不立即盲重发；确认后人工受控恢复，避免重复通知 |
| 事件重投/重复核销请求 | 命中同一任务/消息幂等键，不重复发送 |

目标是在提交后尽快生成任务并尝试触达；“微信接口接受”不等于消费者已阅读。无授权情况下无法保证每次核销均有微信消息，产品验收应分别覆盖授权可发和不可发原因可见。具体模板ID、字段及真实授权能力需要上线前在对应小程序后台核验。

## §10 开发计划（执行顺序与依赖）

| 阶段 | 编号与工作 | 模块/交付 | 依赖及完成标准 |
|---|---|---|---|
| P0 设计冻结 | C01统一原型、C02接口review、C03微信流程 | 本文、9屏原型、UI目录 | 本次完成；实际微信模板及身份绑定仍待核查 |
| P1 查询后端 | C04身份绑定与索引核查；C05消费者混合账单；C06Core公开查询及focus定位 | Pay/service/client/internal；Core/AppService/Controller | 无权限放大、分页稳定；旧空短号、零余额可查；明确无记录404与空筛选区别 |
| P2 通知后端 | C07核对事件同事务；C08通知事实查询；C09场景+任务幂等+跳转；C10微信模板映射、能力接口 | Pay事实读取；Core事件桥接/消息中心；shop-notify/SPI | 依赖C05及真实模板配置；成功后才发；消息失败不回滚消费；重投无重复 |
| P3 C端 | C11我的入口+门店列表；C12账单/类型/时间弹框/定位；C13自主微信授权 | C端端点表、HTTP、mock、页面、三语文案 | 依赖C06/C10；复用统一组件；H5不伪造微信授权；登录返回原记录 |
| P4 定向联调 | C14身份/游标/日期；C15核销事件及通知；C16页面状态 | 编译、基本单测、C端类型检查、集成记录 | 只测新增与修复；已通过旧链路不重复回归，不跑全量 |
| P5 发布验收 | C17提交授权分支；后端与H5发布；小程序真微信验收 | 版本、部署记录、可回滚包 | 先部署兼容后端再发C端；H5上线不代表小程序微信通知已发布 |

定向验证：跨用户/租户/门店拒绝；离线会员绑定前后归属；相同时间翻页；Tab与日期切换丢弃旧响应；焦点号定位和越权；成功/回滚/重复核销；事件重投、同业务不同eventNo；微信无授权、配置错、暂时失败、未知结果；实际点击通知登录后定位。真微信发送使用指定测试用户主动授权及已批准模板，普通接口联调不创建真实资金流水。

数据库评估：先复用sv_statement、sv_user_store_index、现有通知任务/日志/订阅记录；核查消费者索引及通知业务幂等唯一约束。确需字段或唯一索引时新增Flyway迁移，版本号在开发时按当前最大值分配，不能现在固定或修改已应用脚本。

发布顺序：兼容的Pay事实/查询 → Core消费者API及通知任务 → C端H5 → 小程序构建上传、审核/体验版与真实微信验收。能力未配置时返回明确不可用状态。前端页面与微信通知分项记录验收，未完成项不能写“全部上线”。

## §11 开发执行记录（2026-10-21）

基线提交：1e76d6d0d。开发使用独立worktree codex/store-stored-value，不在共享工作目录中修改。

- 查询执行包：C04～C06及C08；Pay本人混合账单、通知事实查询、本地/远程HTTP合同和Core消费者Controller。
- 通知执行包：C07、C09、C10；基于已提交核销事实的通知场景、业务幂等、微信通道与能力查询。
- 前端执行包：C11～C13；本人门店、账单筛选/定位、主动微信授权，统一组件与三语文案。
- 集成执行包：定向测试、身份与分页审查、生成契约/目录、合并授权远端最新变更、部署及只读验证。

Ruling：发现远端较基线新增13个积分相关提交，交付前统一整合并保留现有积分发布功能；不能直接以旧基线部署覆盖。整合引起的兼容修改只验证相关编译及定向场景，不重新跑全量回归。

微信真实模板和接收用户的授权不能由原型生成。代码与H5可在模板未配置时发布为通知渠道不可用；真实微信发送必须单独验收并如实记录，不能以站内消息成功代替微信送达。

补充核对：HEAD已含MpStoredValueController；stores旧StoreItem响应已有entityNo/storeStatus/lastActivityType/lastActivityAt。实现应兼容旧字段并新增规范化lastOccurredAt/lastType，不能因新C页面直接删除已有公开字段。新混合记录DTO继续保持消费者白名单。

Ruling：OpenAPI规范校验拒绝新路径复数资源后接id，尚未发布的混合账单接口定为 /mp/stored-value/store/{storeNo}/records；目录 /mp/stored-value/stores 保持不变。前后端及文档同步，不放宽已有规范守卫。

Ruling：消费通知设置在账单页以sh-sheet承载，不新增独立通知设置页面；原型s09仅表示弹框内容。减少返回层级，保持账单为主线。

### 11.1 本轮编码与验证结果

查询：新增StoredValueConsumerQueryService、本人账户关联SQL、StoredValueConsumerViews、本地/远程AppService及Pay内部HTTP合同；扩展原MpStoredValueController，旧目录字段兼容，新增规范化字段和混合账单/focus。绑定沿用既有账户和目录回写，不更改历史账单身份。

通知：新增notify_stored_value_task（V543），tenant+usage+recipient+scene唯一；任务、站内消息和授权预留落库后确认事件，外发在事务外执行；SENDING滞留变UNKNOWN，UNKNOWN不自动再发。新增STORED_VALUE_REDEEMED场景、能力查询、网关字段映射和现有JobHandler/JobDeclaration注册。当前v1事件管道已限定MAIN，因此通知沿用MAIN并验证权威消费归属，未宣称扩展跨租户事件模型。

C端：两页及“我的”入口、三种类型、时间sh-sheet、分页重试与并发响应丢弃；账单按门店时区显示，目录注明显示时区；401局部最多恢复重读一次，失败进入已有登录并保留通知落点；通知设置复用账单底部弹框。金额使用币种最小位，默认不逐条展示单号，不展示本金赠金/独立核销码。

| 验证 | 已核对结果 |
|---|---|
| Pay本人SQL | 2/2通过；去掉账户store/entity条件消融失败，恢复后通过 |
| Pay内部真HTTP合同 | 6/6通过，含新账单/通知事实及错误服务Token401 |
| 通知定向组 | 25/25通过：任务6、gateway8、subscriber3、真实H2通知6、场景种子2 |
| 通知消融与恢复 | UNKNOWN改FAILED后1项正确失败；恢复后任务6+订阅/capabilityHTTP1通过 |
| 真实应用装配 | ConsumerStoredValueFlowTest 1/1通过，真实到账→分页→短号定位→跨用户拒绝及筛选游标拒绝 |
| C端 | 6文件17项通过；vue-tsc通过；最终H5与MP构建均成功，包含最后时区/登录/简化展示修复 |
| 契约与目录 | C端OpenAPI生成成功；原型登记与UI目录校验通过；git diff --check通过 |

独立review发现并修复两个P2：门店日期显示与筛选时区不一致、401会话恢复不自动读取通知定位。相关新增测试已通过，未重复全量回归。

H2新增V543段使用scoped generator生成并追加；全量schema generator受已有V538动态SQL语法阻塞，未修改已应用迁移，也不声称全量生成成功。小程序构建依赖该worktree已有约定的@ai-shop/ui本地软链，补齐后通过，无tracked构建配置变更。

实际微信渠道默认关闭，模板默认空。配置项WX_TPL_STORED_VALUE_REDEEMED及WX_TPL_STORED_VALUE_REDEEMED_FIELDS需与已批准模板核对，匹配appid、场景开关及有效授权后才available。未配置时返回不可用，站内记录仍可用。尚未执行真实微信发送；发布与环境验收结果另记。

### 11.2 远端整合验证

已整合远端437136b8e（包括已发布的积分账户与订单变更），保留C端积分服务端开关。合并后的Core/独立Pay及依赖编译通过；C端vue-tsc通过，仅重测冲突涉及的储值API契约1项通过；OpenAPI重新生成120路径/128操作/412schema。统一UI目录284页面、原型总览44份校验通过。补登记远端已存在的points-bc-accounts原型及3个锚点，没有改变积分产品逻辑。

额外生成检查：模型对账生成器因原有MemberSetting→mbr_setting字段匹配失败而未完成；ERD全量生成出现其他域的历史漂移，相关无关产物已恢复。新增任务表的设计以V543及本节实现记录为准。本次没有宣称全项目生成物闸门通过，也未修改已有欠账基线。
