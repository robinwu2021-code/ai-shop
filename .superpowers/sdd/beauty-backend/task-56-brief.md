# Task56 共用普通服务报价、建单与受管理履约

状态：55D已独立复审通过并提交31479219d；本brief用于Task56先设计后实施，不表示56能力已交付。以主TDD §2.22 Task56、bm04-checkout-design.md及task-56-preflight-draft.md为绑定；旧方案56a/56b统一按正式56/57分开，付款属于57。

## 先设计再实施

先读本brief——这是要求与准确值。唯一目录 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，分支codex/beauty-foundation，不碰原cwd及其它会话。派发时根给准确BASE。只执行56，不读取/执行整个主TDD。你是唯一实施者，不派子agent。遵守项目开发规范及Java零售规范，服务直接本域Mapper、不造Repository；按契约档位更新TDD/ADR/三份产物及生成真源。

先完成task-56-design.md和task-56-report.md，列实际类型/签名、表与约束/追加迁移SQL提案、身份与事务/锁序、Bean图、状态与恢复、完整经济摘要、真实AC/selector、文件归属，再发根技术核对。设计放行后直接RED→编码→GREEN，不再向用户请求已有范围的批准。不要先生成未来空接口/Controller/Service。启动设计时核对当前零售与餐饮实现、相关分支实际状态；发现上游差异记录根裁决，不盲merge/切分支或覆盖其它会话文件。

## 56真实范围

1. 共用交易owner负责报价、C确认、普通销售建单、原操作查单、稳定来源行、受管理服务完成守卫。仍用ord_order/ord_sub_order/ord_item；美业只消费共用合同，不新增美业订单账/价格引擎/应收支付账。beauty接待工单不等于共用销售订单。
2. 第一阶段CheckoutPort只交付真实六方法：prepare/readQuote/confirm/place/findOperation/source。其FQN、前六准确签名、DTO字段沿bm04-checkout-design.md §3；initiatePayment/findPayment为57真实增量，不提前放空方法或假成功。OfflineReceiptPort、PaymentOperationPort、支付request表/现金权限与资金处理属于57，本步不以提前声明算交付。
3. MAIN/CNY、单主体单店、普通SERVICE、STORE_VERIFY、COUNT正整数销售份数；最多20行且SKU不重复，单行quantity1–999。实际商品/目录必须支持，不改SKU能力、不fallback旧APPOINTMENT/STORE_PICKUP或旧slot。拒EA/分钟/小数/缺失单位的隐式转换；既有OrdItem没有uom，不为泛化给它加列。
4. 稳定sourceLineNo在真实OrdItem插入获id的同TX写映射，禁止事后按SKU/顺序猜。退款/取消后的可用数量按真实owner事实算，不把ordered永久当可服务。未知实收/欠收为nullable，不能把0当未知或伪造PAID/资金receipt。source返回真实历史订单/行/owner事实，BM04执行仍NOT_STARTED/BLOCKED。
5. 普通已支持金额活动/券复用现算法；积分/储值/售卡/赠品回退/group/period/复杂组合不在本期，prepare明确可见、place再核；不能到付款后才拒或静默削减C确认内容。不要把BAPI089全优惠矩阵称完成。

## 身份、权限与HTTP合同

6. confirm/place仅真实HOST当前C session；没有可伪造BuyerContext/用户号自报/DelegatedIdentity当C。严格读取SecurityUtils已验证token details，再TokenStores.of(CONSUMER).get核当前session/tenant/user及active facts；手造LoginUser、B USR、service key、撤销token均拒。不得持久/打印token。
7. B prepare新增trade:checkout:prepare；readQuote/findOperation/source沿biz:order:view加真实订单数据域和当前entity/store权限，CheckoutScope本身无授权含义。OWNER仅沿现有*，MANAGER/CLERK/PICKER/COURIER/CS不自动加权限。customerRef消费真实共享owner严格引用，不能以本域guest/cr1代替C购买授权。普通本地/内部B入口都核当前可信actor及MerchantOperatorAccessPort。
8. HOST /mp/checkout下本步真实read/confirmations/orders及B内部prepare/readQuote/findOperation/source，字段/Idempotency-Key按既有设计§4；本步不支付路由。C写不开放internal RPC，独立美业不能用merchant委托发C购买确认。protocolVersion=1，未知版本fail closed；consumer适配/美业公开wire归59，不创建未来空Remote。技术设计须明确56所交owner接口和59所交美业接线，不能用未配置Port宣称能力。

## 单一普通writer与经济seal

9. 使用bm04-checkout-design.md §12覆盖旧§6/§10强制大拆分措辞：保留OrderServiceImpl普通writer，core内部CheckoutOrderWriter.createConfirmedFor及ExpectedCheckoutQuote，doCreate同次实际定价之后、任何持久创建意图/slot/库存副作用之前经济seal。不能preview总价比后调用无guard createFor，不另写一套完整ord创建逻辑。
10. 根已实测导航：当前doCreate1567；discounts1678；creationRecovery.register1816/enlist1824，slot1826/stock1827，主单1831，LineShares1942。行号仅导航，设计重新核对。seal必须早于register，不只早于insert/stock。序号消耗不代表业务订单/资金事实。
11. 同次LineShares提前纯计算并复用到真实行，原算法/行顺序/同余数规则不变。完整经济hash覆盖实际buyer/entity/store、goods/sku/COUNT qty/价格/行gross与paidShare、主子金额、discountPlatform/Merchant、活动券选择与分摊、payMode/fulfillment/authority；排除quoteNo/orderNo/随机号/生成时间，expiry另核。同额换商品/补贴出资者也须stale。
12. 持久operation→quote→confirmation绑定与current buyer核验、锁序、消费、普通order/line/managed metadata/来源映射必须最终同TX；失败不消费，丢响应查原长期映射，不被24h幂等缓存过期或OrdCreateIntent RELEASED复活成新单。已CREATED重放返回原单不按今价重算。
13. 锁内RPC为具体设计阻点：审计doCreate全部前后Port实际local/remote（商品/营销/准入/receiver/attribution/card/stock/commit均包括），不能因为hook小就持quote/order锁外调。锁外证据只作准备，最终writer仍封自己实际消费的经济快照。依实际部署设计最小可行边界，既不放宽该要求，也不以本次为由泛改零售定价/引新saga；未解清楚不得开始产品代码。

## 持久事实、迁移、完成守卫

14. ord_checkout_operation/quote/confirmation/line和ord_sub_order nullable fulfillment_authority/source_operation_no按既有设计准确业务约束；新managed=SERVICE_EXECUTION，同普通订单原子登记、历史null保留原义。表/列/索引/phase/DTO所有未定值须先精确设计；不要把报价观察复制为新价格定义或账本。
15. 旧方案预留V538已过时：当前实际平台最大V538为member_reference_actor_namespace，启动时再查实际最大号，追加而不回改；pay迁移/金额账留57。新增SQL首次执行前根独立审阅并固定SQL/hash，随后冻结；不写无必要迁移/回填，不改美业V1–V7/ready7/27私表。
16. 所有旧完成路径必须读持久authority；ManagedServiceCompletionPolicy在目标COMPLETED的CAS前检查，底层StatusCasMapper条件写兜底，不能只测新policy或在fired.changed后检查。pickup/merchant delivered/当前C receipt/auto/signed/intervention/direct CAS都核实际入口；无boolean override或伪完成proof。managed付款不能发可用旧核销码/伪服务完成；合法取消/未付超时/退款沿原owner事实，BM05执行仍关闭。历史未管理零售/餐饮正常回归。

## 验证与交接

17. 按既有§10/§11为56真实创建CheckoutContractTest、CheckoutPermissionFlowTest、CheckoutAuthenticationFlowTest、CheckoutPricingFlowTest、ManagedServiceCompletionFlowTest、CheckoutSourceFactsFlowTest、CheckoutNativeMysqlTest（具体归属和方法精确设计核实）；不存在/0执行/skip不是通过。真实TokenStore+认证filter/HTTP证据，不只MockUser；真实OrderService/product/mapper。明确fixture和外部通道边界，不声称部署过全owner。
18. 行为RED先于实现，compile/setup/环境失败单列；重点真实唯一键/同键和异键并发、丢响应重启原号、缓存过期、变价及同额改出资在副作用前拒绝、行分摊一致、持久managed底层CAS和所有旧入口反例、零售/餐饮未管理正常路径。至少一个真实防线消融RED→还原GREEN，报告区分前置RED。
19. 本机cached mysql:9.7.2，普通DISK64M，不用失败128MiB低内存；各批只读Colima MemAvailable>=512MiB，单自有UUID串行、pullnever/localhost/readiness/finally只清自己，不动他人容器/VM/远程。CHECKOUT_NATIVE_MYSQL_*按实际新runner/fixture准确设计；不要运行会drop共享autotest的scripts/test-on-mysql.sh。JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。
20. 运行与变化相符的owner/retail/food/架构/三语/SQL/生成/迁移检查；旧OpsPoints/V506和beauty/store宿主欠账必须诚实保留给60，不放宽棘轮也不声称全backend绿。SQL现gate只扫backend，插件定向覆盖是60另项。不要无新风险重复大套件。
21. 持续更新task-56-report.md，含逐AC方法/实际commands/logs/counts/未验证边界/精确owned。根mainTDD/progress/brief/整体方案/reviews是根文件，不进产品stage；设计/实施报告为你owned。未commit快照tree和diff交根独立spec/quality审查，放行才精确产品commit；不push/merge/共享或生产部署。大模块部署统一60；56不等于BM04完整交付，57资金/58绑定账单/59协议/60模块验收顺序不变。

## 既有身份安全合同补充（设计审计绑定）

沿ADR-045及已完成Task54/55：customerRef使用真实共享strict引用，持久冻结只留规范buyer/可信scope与必要安全摘要；cr1/mc1、claimToken、联系方式、当前session/token不落表、不进入日志或DTO.toString，不能当C购买授权。精确设计须明确首次引用核验与原号同键重放的顺序/摘要，避免为重放重签身份或悄改buyer。此为已有共用合同延续，不增加新owner协议。

## 餐饮基线技术裁决（2026-10-23设计审计）

已知本地餐饮分支codex/food-next01=fbda26d2，origin只返回codex/ai-shop-codex=96c94cd18efab05b613faff1111238e88f141b54，无独立food/beauty远程分支；不把本地日期描述成远程最新。OrderServiceImpl、GoodsQueryPortImpl、CampaignPortRouter、CouponPortRouter在该本地餐饮分支与本HEAD相同。餐饮侧13项trade新增978行归9f5140dff/fbda26d2f，属于尚未接线的pricing配置/发布与纯公式引擎；不解决本步显式buyer或PAY优惠路由。整个food差异479文件且V532/V533与当前已用迁移冲突，本步不整体合并、不强制引入未接线引擎。基于已相同的实际普通writer最小扩展并复用同次算法，明确记录差异/成本，不宣称完整餐饮分支合入。精确证据清单由design/report保存。
