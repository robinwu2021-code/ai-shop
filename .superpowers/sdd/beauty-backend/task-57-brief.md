# Task57 资金原支付号恢复与正式线下实收

状态：Task56已实现并独立审查通过，产品提交dbb635a7cf9d36ddddfbd0138e8dfc40216d4e9b。这里是Task57设计/实施要求；付款能力尚未交付，不表示已部署。唯一工作树 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，分支codex/beauty-foundation。派发BASE由根提供。只执行57，不重做55/56，不进入58绑定/59美业wire/60部署。

## 先设计再实施

先读本brief。绑定来源：主TDD Task57原文（本brief已承接）、bm04-checkout-design.md §3支付分支、§7资金原号、§8正式offline receipt及§10–11相关AC。旧文中的56a/56b按正式56/57理解，旧预留迁移号已失效。Task56真实源码及task-56-design.md、task-56-report.md §21–23为已交付基线，不把旧草稿字段/签名强行覆盖真实六方法。

你是唯一实施者，不派子agent。遵守CLAUDE及项目Java/零售规范，Service/Impl与本域Mapper、统一错误/三语，不造Repository/第二账本/未来空接口。先只写task-57-design.md和task-57-report.md：准确类型/方法/DTO/HTTP/Bean图、数据源/事务/锁序、所有旧新open调用图、状态/恢复、候选SQL逐字文本、权限/evidence来源、真实测试与精确owned。根技术核对并独立固定新SQL后才RED→产品编码；不向用户重复请求已授权范围批准。

## 真实范围与硬边界

1. 共用资金owner在真实stl_payment上实现持久payment operation→paymentNo/outTradeNo/receipt映射，不新增美业支付账/金额余额。交易owner使用56已由本人确认的普通订单，金额、币种、buyer/entity/store/payMode从owner事实取，不信请求自报金额或美业传PaymentOpen。
2. 只在这一步给真实CheckoutPort增initiatePayment/findPayment及对应当前HOST C入口；前六方法及稳定来源行不重写。新PaymentOperationPort prepare/initiate/query/confirmOffline、正式OfflineReceiptPort confirm/find按绑定方案逐字段对账后冻结，真实实现，禁止空成功/无配置Remote占位。C新支付或取参数只真实当前CONSUMER session，复用56真实TokenStore身份检查；不把DelegatedIdentity、服务key或buyerNo自报作为C，不开放internal C购买/支付授权。
3. trade调用资金前，短事务持久原requestKey→payment operation/摘要映射；原键原payload永久查询原结果，冲突明确，不因24h缓存/重启/超时新号。首次并发与重复键靠真实唯一键；明确actor namespace/请求归属/重放撤权边界。不得跨库事务包RPC。
4. pay先持久PREPARED冻结指令，P2在同pay事务锁order guard→operation，ledger.open与operation绑定paymentNo/outTradeNo一起提交。guard覆盖所有旧/新open，不仅新API；不同key/旧新入口不能并发创建第二未终态attempt。guard是序列化事实，不是余额账；不能用退款容量guard替代。审计旧open是否自调用/独立事务及异常回滚，勿制造ledger已建但mapping空的窗口。
5. 调gateway前先独立提交SENDING/send_started_at，再锁外prepay。进程丢失或超时/不确定false/暂未found保持UNKNOWN/原号查询，不close/重新open/换outTradeNo。只有可证明前attempt终态并且真实C显式新key才允许合法新尝试；同号取参数必须按真实channel幂等能力，不推测可安全重发。
6. query/callback用真实gateway结果核原号、金额、币种、tradeNo，复用ledger.settle/原订单收款投影，不另写状态。lock内不得调用RPC（包括SettlePort未来remote）；先锁外读取不可变真实receipt，再短事务核owner当前状态/范围/金额及proof匹配。不给managed旧offline trust分支加boolean bypass或伪proof。
7. OfflineReceiptPort只能当前当店、具有新pay:cash:receive权限的实际操作账号；MCH主体号不当经办账号。OWNER仅沿已有*，其它默认角色不加权；RECEIVE/订单查看不能授收款。三语、LABELS、mock/生成真源同步，不能因现静态scanner仅识别biz前缀就称新pay码已覆盖。
8. 必须先C确认并place OFFLINE订单，才可足额实收；金额/币种取owner待收，拒拆收/找零/混合/改应收。CASH/BANK_TRANSFER按真实正式门店策略，transfer必须可信evidenceRef，现金有正式经办/原因留痕。审计实际凭证owner/附件事实与可信范围，不把任意字符串或前端上传URL当资金证明；缺必要能力先明确设计闭合，不假成功。
9. pay事务内以正式授权的ledger.open+settle产生真实SUCCESS receipt/唯一receiptNo，保存method/evidence/实际actor/receivedAt等审计，金额仍由stl_payment拥有。重复返回原receipt；已关闭订单/晚到实收保留真实资金并进入既有late-payment resolution，不能掩盖已收或再收。旧managed MerchantOrderService.confirmOfflinePay与共享offline快捷信任继续拒绝无正式receipt的请求。
10. 真实managed在线/正式offline成功收款能正确投影已付，但不得生成可用旧核销码/伪服务完成，不解除SERVICE_EXECUTION最低CAS。source扩充真实支付操作/receipt字段保持56的RR/UNKNOWN/历史金额合同，不把UNKNOWN填0或旧可信pair当当前可服务。BM05仍关闭。

## 迁移、验证与交接

11. 当前平台最新已提交V539，8444bytes/SHA256 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b 已执行冻结；beauty V1–V7/ready7/27私表冻结。启动时查平台及独立pay各自真实迁移最大号/数据源归属，再提出追加SQL，不直接照抄旧V538/V539预留。根首次执行前独立审DDL/hash，之后只追加不回改；不用IF NOT EXISTS/clean/repair任意baseline掩盖漂移。
12. RED先于实现，编译/setup/环境失败单列。真实MySQL ledger/Mapper和实际trade owner；只外部第三方channel允许隔离TEST/STUB/故障代理，不操作真实外部资金。最少AC：same/different key与旧新open并发；ledger/open与映射原子；发送前崩溃/响应丢失/独立进程重启仍原outTradeNo；UNKNOWN不新号；callback/query同一receipt；真实C与cash授权/撤权；凭证真实性/范围；offline重复与关单晚款resolution；managed收款不完成与原source事实。每项设计真实selector，不以不存在/0执行/skip称通过。
13. 覆盖PaymentOperationNativeMysqlTest、OfflineReceiptFlowTest、PaymentInitiationCashRaceFlowTest、PaymentLedgerCloseRaceFlowTest及旧资金/现金/checkout受影响回归，确切实际类/方法在设计审计后列明。至少一个真正防线最小消融RED→逐字节还原GREEN，区分前置RED。支付的独立进程崩溃/原号恢复证据不能仅以删除cache或新Service对象代替。
14. 本机cached mysql:9.7.2、普通DISK64M；正确Docker context colima-hxkey-tests/profile hxkey-tests；各批MemAvailable>=524288KiB，只自有UUID串行/pullnever/loopback/随机独立库/秘密env/finally清自己，不停其它容器/改VM/动远程或共享autotest。JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。
15. 56教训：真正native-only测试在具体类显式条件隔离，普通Maven不误跑；专用runner强制真实native且核新报告非零/0skip/0failure/error。保留错误native配置硬失败，不吞错误、不把skip当资金验证。不得改全局POM排除/放宽checker来造绿。
16. 与变化相符的pay/trade/零售餐饮/架构/三语/SQL/生成验证。56报告§20与根进度列出Task60基线债：OpsPoints FQN、store迁job.config与beauty域登记、旧V506/实体CHECK解析、12表清单、预约权限三语、common.delete、库存文案参数、生成漂移、插件SQL定向覆盖、CLAIMED→USER+真实REPLACE及55D M1告警分类。保留准确归属，不称全backend绿；本任务新增失败必须本步修，不借旧债遮掩。
17. report持续记录逐AC、实际全commands/logs/counts/SQL冻结/firstGREEN与消融/未验边界/精确owned。根mainTDD/progress/brief/preflight/整体方案/ADR/reviews不进产品stage；你的design/report owned。完成后未commit冻结tree+精确diff交根独立spec/quality审查，放行后才精确提交，不push/merge/deploy。整个BM04真实JAR部署仍60，57不等于整个美业资金与来源闭环完成。

## 设计审计补充裁决（2026-10-23）

正式线下收款按既有PRD纯记账：授权当前商户确认已外部实收，资金owner落receipt；不表示银行独立核验或钱经过平台。BANK_TRANSFER保留，但要求真实owner附件事实及原订单/操作/方法/实际经办/时间的不可变用途绑定；附件独自不能证明到账。沿docs/technical/TDD-线下收款商家开关.md及PRD-支付方式与预约预定§7.1、PRD-支付域AC3.6/6.3保留四层经营开关、无平台补贴、不抽佣/分账、排除通道对账及影子记账。

早期只读审计已发现旧上传uploadedBy是merchant主体号、旧经营操作Port不暴露SQL已有account_no；必须在真实owner扩最小能力取得当前实际account，保留既有Port签名兼容/数据域/caller-TX禁止，不伪造账号。专用私有收款凭证上传、真实媒体事实核验、保留/删除闭环的精确提案由设计审查，不泛改历史上传或造媒体账。

旧recon暂未found会closeUnpaid，close为REQUIRES_NEW；设计须让SENDING/UNKNOWN保守原号查询，并避免外事务持guard后调用新连接close自锁。所有旧/新派发共用原attempt发送仲裁，多个合法request共享pending时须准确设计canonical operation/别名/不可变hash关系，不允许各op对同outTradeNo重复发送，不把paymentNo改一对一unique违背既有合同。以上是设计闭合要求，不是产品代码或SQL执行放行。

最终技术核对补充：资金旧owner按全局orderNo/outTradeNo查找，BaseEntity一期MAIN只是审计，不存在已启用的行级租户隔离；guard全局orderNo唯一、operationNo按真实全局Port查询键唯一，LEGACY不被新MAIN硬CHECK截断。现有旧unmanaged OFFLINE确认没有新stl receipt，但线上WAIT_PAY与线下WAIT_OFFLINE_PAY无互转，唯一实际init caller先要求WAIT_PAY；保留旧语义并补真实反例，不把RECEIVE升级成正式现金权限。

已有原单实收以已消费确认→原quote→原order/sub/items一致应收为准，不重新读取商品现价或以当前下架/删除/门店开关后来关闭拒绝承认已收；原quote/place已通过正式四层准入。关单前后首笔实收都保留真实receipt及原late-resolution；原amount/currency不得改零或重算。精确512行设计已通过根技术核对，V540候选7477字节/hash见独立pin；实际文件生成后须根再复算一致才首次执行。

## 实施裁决：正式实收与专用凭证的时间基准

原 OrdOrder.createdAt 由 AuditMetaObjectHandler 使用 JVM 本地 LocalDateTime.now() 写入，未保存 offset；不得在新资金合同中将其硬解释为 UTC。原 CONSUMED confirmation.consumedAt 由 CheckoutQuoteGuard.complete 在真实建单同一事务内使用 CheckoutClock Instant→UTC 持久化。正式实收 receivedAt 下界采用该原确认 consumedAt 的 UTC instant，缺失时拒绝，不推测旧审计时间。代价是下界比订单行创建略晚，事务内极小区间不满足此合同；不得改写真实 receivedAt 绕过校验，也不得因此删除已经接受的资金事实。测试覆盖 JVM +08、边界与原事实恢复。

专用 OFFLINE_RECEIPT 的 MediaUploadService 新用途写入明确 UTC createdAt/上传时刻，旧用途保留既有时间语义；允许将此实际 owner 文件最小追加到 owned 清单，不调整全局审计时间。不可变凭证摘要不使用会变化的 updatedAt。无 DDL 变化，V540 继续冻结。

## 实施裁决：真实成功回执的内部订单投影

实际 DataScopeRegistration 对 ord_order 只登记 SELF/COMMUNITY，正式商家收款 HTTP 上下文会使内部订单投影读不到原单。允许仅在 PaymentDispatch.project 已获得真实资金 owner SUCCESS receipt 后，对内部 OrderRepairPort.markPaid 使用 executeWithoutScope。公共收款接口、当前 B/C 身份/门店/收款权限与原指令校验保留；OrderService 仍在锁外重新读取真实回执、锁内校验订单/买家/金额/币种/通道/交易号。不得扩散到任意 PaymentOpen、公共查询或全局放宽数据域。关单迟收及不匹配仍保留原资金事实与处置记录。测试覆盖合法当店 B 投影成功及越店拒绝。

## R1首次线上发送授权点

对 PREPARED/OPENED（包括别名所指 canonical 根）且尚未开始发送的操作，当前真实 C 重放也要在 trade owner 读取并通过原确认/原订单经济与范围、ONLINE、实际待付状态与期限检查。该检查成功是本次首次发送授权点，之后才无调用方事务/锁地进入 pay 原号仲裁；授权之后发生的取消属于既有迟收竞争。不得重算冻结价格或通道路由，也不得以检查失败删除永久 P0/P1、关闭 UNKNOWN 或隐藏实际 SUCCESS。已开始/未知/已收的请求继续只查原号；已付状态的新键不能制造永久无法绑定、污染成功来源的 PREPARED 映射。实现和原生用例由 R1 验证，未先验认定修复通过。
