// 端点 → 细粒度权限码的**登记表**。权限码细化改造（阶段 A）的输入。
//
// 原则：**权限码对应界面功能**，而不是对应「一整个域」。
// 现状是 16 个码盖 147 个端点，粗到「只读商家档案」与「封禁商家」同码。
//
// 三条写在前面的取舍：
//
// 1. **读写分开**。GET 给 `:read` 码，写给动作码。
//    这不是洁癖 —— `Perms.COMMUNITY_VIEW` 的注释记着实测过的代价：
//    读写合一时，只要有任何一个角色需要看而不需要改，它就会被迫多拿一份写权限，
//    或者干脆做不了本职工作（BD 打开审核抽屉，覆盖小区一个选项都没有）。
//
// 2. **码名取自 ops-web 的 `uiPermCode`**（64 个，与菜单项一一对应）。
//    那份码表一直在维护，后端从来没采纳它。另起一套等于再造翻译层。
//
// 3. **没有菜单项的端点也要有码**，码名按它实际干的事起。
//    这类有 28 条（保证金、支付额度、准入策略、进项发票、经营模式…）——
//    **后端有能力、ops-web 没有入口**，是「有能力没有消费方」的又一批，
//    见文件末尾的 NO_UI 清单。给它们编码不是为了配菜单，是为了别再共用一把大钥匙。
//
// 规则按**从具体到一般**匹配，第一条命中的生效。
// 顺序敏感 —— 所以下面每一组内部都是「特例在前、兜底在后」。

/** [方法或 *, 路径正则, 权限码, 理由（只在不显然时写）] */
export const RULES = [
  // ── 商家治理 ───────────────────────────────────────────────────────────
  ["*", /^\/ops\/merchant\/apply/, "merchant:apply:audit"],
  // 封禁 / 归档 / 记违规 = 处置。**与只读档案分开是这次改造最主要的目的之一**
  ["POST", /^\/ops\/merchants\/[^/]+\/(status|archive|unarchive|violations)$/, "merchant:merchant:ban"],
  ["POST", /^\/ops\/merchants\/[^/]+\/verified$/, "merchant:verify:grant"],
  // 资质授予：类目上挂着 required_code，授资质等于放行一整类商品的准入。
  // 读单独一个码 —— 商家档案页要显示「这家店有哪些资质」，看不代表能授。
  //
  // **注意与 `/ops/auth-codes`（资质码字典）分开**：那是「世界上有哪些资质码」，
  // 归类目维护面；这里是「给这家店授哪几个」，归商家治理。
  // 第一版把两者归成同一个码，自检发现它**横跨 category:manage 与 merchant:audit
  // 两个粗码** —— 机械展开时会让只有其中一个粗码的角色白拿另一半，
  // 那正是这次改造要防的静默放宽。
  ["GET", /^\/ops\/(merchants\/[^/]+\/)?qualifications/, "merchant:category:read"],
  // 没有 GET /ops/merchants/{no}/auth-codes（只有 PUT）—— 写过一条，被死规则守卫抓出来了。
  // `GET /ops/merchants/auth-codes` 是另一条路径（少一段），落在下面的档案只读上
  ["*", /^\/ops\/(merchants\/[^/]+\/)?qualifications/, "merchant:category:grant"],
  ["*", /^\/ops\/merchants\/[^/]+\/auth-codes/, "merchant:category:grant"],
  // 资质码字典本身，与类目树同一个维护面（类目上挂 required_code，改哪个都影响准入）
  ["GET", /^\/ops\/auth-codes/, "product:category:read"],
  ["*", /^\/ops\/auth-codes/, "product:category:update"],
  ["POST", /^\/ops\/qualifications\/[^/]+\/revoke$/, "merchant:category:grant"],
  ["GET", /^\/ops\/merchants\/[^/]+\/store-modes$/, "merchant:mode:read"],
  ["PUT", /^\/ops\/stores\/[^/]+\/business-mode$/, "merchant:mode:update",
    "现在挂在 settle:manage 下 —— 改门店经营模式根本不是结算"],
  // 资金路径（轴②：钱先进谁的账户）。与经营模式共用一组码 ——
  // 两者都决定钱怎么走，分成两套权限只会让配的人漏配其中一半
  ["PUT", /^\/ops\/merchants\/[^/]+\/funds-mode$/, "merchant:mode:update"],
  ["GET", /^\/ops\/merchants\/mode-risk$/, "merchant:mode:read"],
  // 自营应付账款。**制单与付款分权**：确认对账用 settle:execute，
  // 而登记付款用 payout:execute —— 今天两个码都在 FINANCE 一个角色上
  // （Perms 的注释里记着这是「改配置解决不了」的一条），但登记表按码走，
  // 将来拆角色时不用再回来改这里。
  // 四轴对账总览。与 recon-diffs 同一个码 —— 它们是同一件事的两个视图
  ["GET", /^\/ops\/payments\/recon-axes$/, "finance:recon:read"],
  ["GET", /^\/ops\/payables/, "finance:settle:read"],
  ["POST", /^\/ops\/payables\/[^/]+\/confirm$/, "finance:settle:execute"],
  ["POST", /^\/ops\/payables\/[^/]+\/paid$/, "finance:payout:execute"],
  ["POST", /^\/ops\/payables\/[^/]+\/no-invoice$/, "finance:invoice:verify"],
  // 进项票：它决定平台能不能付款，所以核验权与开票权同一个码
  ["GET", /^\/ops\/purchase-invoices/, "finance:invoice:read"],
  ["POST", /^\/ops\/purchase-invoices\//, "finance:invoice:verify"],
  // 买家开票申请（按订单走），与上面那张进项票是反方向的两张票
  ["GET", /^\/ops\/invoice-requests/, "finance:invoice:read"],
  ["POST", /^\/ops\/invoice-requests\//, "finance:invoice:verify"],
  // 积分资金看板。**是资金表不是营销表** —— 读它的是财务
  ["GET", /^\/ops\/points\/overview$/, "finance:settle:read"],
  // 类目策略：能不能当面付、发多少积分。挂**类目**码不挂规格码 ——
  // 改「这一类能不能当面付」与改规格绑定不是同一类决定
  ["GET", /^\/ops\/category-pay-modes$/, "product:category:read"],
  ["POST", /^\/ops\/category-pay-modes\//, "product:category:update"],
  ["GET", /^\/ops\/category-points$/, "product:category:read"],
  ["POST", /^\/ops\/category-points\//, "product:category:update"],
  // 积分端策略（哪个端不发放/不核销、线下能不能抵）。挂在结算码下而不是营销码下：
  // 关掉一个端的发放，减少的是**平台对用户的负债**，那是资金决定不是活动决定
  ["GET", /^\/ops\/points\/client-policy$/, "finance:settle:read"],
  ["POST", /^\/ops\/points\/client-policy$/, "finance:settle:execute"],
  ["GET", /^\/ops\/merchants/, "merchant:merchant:read"],

  // 店招公告审核。**后端其实有**，而 P-10.1 在三方对齐里被记成「后端零实现」——
  // 那份 review 这一条是错的，门店主页域里审核这两条一直在跑。
  //
  // 这里**故意不拆读写**（是全表唯一的例外）：待审队列的「读」就是审核这个动作的一半，
  // 拆出来会得到一个只有审核员用、且审核员必然同时持有的码 —— 那种码只增加配置负担。
  // 对比 `GET /ops/goods/audit-queue` 归 `product:sku:read`：商品池本身是个浏览场景，
  // 有人只看不审；店招待审队列没有这种角色。
  ["*", /^\/ops\/stores\/audits/, "store:page:audit"],

  // ── 门店档案（P-11.2.1，V96）────────────────────────────────────────────
  // 解除强制下线 = 处置动作的另一半，与压下（violations 里的 STORE_OFFLINE）同码
  ["POST", /^\/ops\/stores\/[^/]+\/restore$/, "merchant:merchant:ban"],
  // 档案与经营数据挂商家治理的只读码：门店维度的端点按「这件事属于哪条业务线」归码，
  // 不按 URL 前缀归码（business-mode 挂 merchant:mode:* 是同一个道理）。
  // store:page:audit 不能借 —— 那是店招审核动作的钥匙，塞进只读档案等于「能查店 = 能审店」。
  // 必须排在 /ops/stores/audits 与 business-mode 之后：规则第一条命中生效
  ["GET", /^\/ops\/stores(\/|$)/, "merchant:merchant:read"],

  // ── 增值包与门店额度（P-11.2.2~11.2.6，V150）────────────────────────────
  // 授予套餐 / 覆盖额度 = 处置面：决定一家商家能开几家店，与决定他能不能营业同一量级
  ["POST", /^\/ops\/merchant-plans\/[^/]+\/grant$/, "merchant:merchant:ban"],
  ["PUT", /^\/ops\/merchant-plans\/[^/]+\/quota$/, "merchant:merchant:ban"],
  ["GET", /^\/ops\/merchant-plans(\/|$)/, "merchant:merchant:read"],
  // **档位定义的写入刻意不与授予同码**：BD 能给某家授予套餐，但不能改「套餐是什么」——
  // 后者影响这一档之后的所有订阅，与「功能开关、灰度、汇率」同级。
  // 读反过来给 merchant:merchant:read：授予对话框要拿档位列表填下拉。
  ["PUT", /^\/ops\/plan-defs(\/|$)/, "system:param:update"],
  ["GET", /^\/ops\/plan-defs(\/|$)/, "merchant:merchant:read"],

  // ── 准入（保证金 / 支付额度 / 准入策略）· 无菜单入口 ─────────────────────
  ["GET", /^\/ops\/admission\//, "merchant:admission:read"],
  ["*", /^\/ops\/admission\//, "merchant:admission:update"],

  // ── 商品与类目 ─────────────────────────────────────────────────────────
  ["*", /^\/ops\/goods\/[^/]+\/audit$/, "product:sku:audit"],
  // 强制下架 = 撤销过审，与审核同一个动作面、同一拨人（P-3.2.3）
  ["POST", /^\/ops\/goods\/[^/]+\/force-off$/, "product:sku:audit"],
  // `(\/|$)` 而不是 `\/`：域根 `GET /ops/goods`（商品池）后面没有下一段，
  // 只写 `\/` 会漏掉它。这个形状会在每个「先有子路径、后来才补域根」的域上重演 ——
  // 而漏掉的后果不是报错，是那条端点**匹配不到任何规则**，
  // 于是权限码细化时它只能留在粗码上，没人知道它被落下了。
  ["GET", /^\/ops\/goods(\/|$)/, "product:sku:read"],
  // sku 粒度的动作（P-3.3 预售 / sku 级审核与压下架）。与 goods 级同一拨人、
  // 同一个动作面 —— ops-web 的 `product:stock:update` 一直就是映到这个码的
  // （perm-map.ts 与 NEAREST_CODE 两处都是），所以这里不新造码
  ["*", /^\/ops\/skus\/[^/]+\/(audit|force-off|presale)$/, "product:sku:audit"],
  // 同 /ops/goods 的形状：`(\/|$)` 才盖得住域根 `GET /ops/skus`。
  // 必须排在上面那条之后 —— 规则第一条命中生效，写反了 oversell 之外的写动作会被判成只读
  ["GET", /^\/ops\/skus(\/|$)/, "product:sku:read"],
  // 进销存的三个运营端只读口（健康度 / 台账 / 对差）。**归 product:sku:read
  // 而不是新造 product:stock:read**：三个 Controller 上的 @PreAuthorize 判的就是它，
  // 这里换个码等于让登记表描述一件与代码不符的事，而这张表正是「谁能访问什么」的判据。
  // 只有 GET —— 进销存在运营端**没有写口**，这一行的窄正是它的意思：
  // 运营改了商家的数，「这个数是谁改的」就多一个答案，而商家不会知道。
  ["GET", /^\/ops\/inventory(\/|$)/, "product:sku:read"],
  ["GET", /^\/ops\/categories/, "product:category:read"],
  ["*", /^\/ops\/categories/, "product:category:update"],
  // 规格模板（P-3.4）。**归类目维护面不归审核面**：模板按品类预置，
  // 与类目树、资质码字典是同一拨人在配。归审核码的话，只有审核员能维护模板，
  // 而审核员不碰类目结构
  ["GET", /^\/ops\/spec-templates(\/|$)/, "product:category:read"],
  ["*", /^\/ops\/spec-templates(\/|$)/, "product:category:update"],

  // ── 交易订单 ───────────────────────────────────────────────────────────
  ["POST", /^\/ops\/orders\/[^/]+\/intervene$/, "order:order:modify"],
  ["POST", /^\/ops\/orders\/[^/]+\/proxy-cancel$/, "order:order:proxy"],
  ["GET", /^\/ops\/(order|orders)/, "order:order:read"],

  // ── 售后 ───────────────────────────────────────────────────────────────
  ["GET", /^\/ops\/after-sales\/fast-refund-rule$/, "aftersale:refund:read"],
  ["POST", /^\/ops\/after-sales\/fast-refund-rule$/, "aftersale:refund:approve"],
  ["POST", /^\/ops\/after-sales\/[^/]+\/decide$/, "aftersale:ticket:handle"],
  ["GET", /^\/ops\/after-sales/, "aftersale:ticket:read"],

  // ── 经营看板 ───────────────────────────────────────────────────────────
  ["GET", /^\/ops\/dashboard\//, "dashboard:overview:read"],

  // ── 履约调度与物流（P-5.1 / P-5.2，V130–V135）───────────────────────────
  // 批次推进与「看批次」共用 batch:read，是全表第二处不拆读写的地方 ——
  // 理由写在 Perms.FULFILLMENT_BATCH_READ 上：ops-web 的发车按钮就是用它门控的，
  // 后端另判一个写码等于造一个「看得见、点下去 403」的按钮
  ["*", /^\/ops\/fulfillment\/batches/, "fulfillment:batch:read"],
  ["GET", /^\/ops\/fulfillment\/sorting/, "fulfillment:batch:read"],
  ["GET", /^\/ops\/fulfillment\/redeem/, "fulfillment:redeem:read"],
  ["GET", /^\/ops\/fulfillment\/overdue-rule/, "fulfillment:batch:read"],
  ["POST", /^\/ops\/fulfillment\/overdue-rule/, "fulfillment:rule:update"],
  ["GET", /^\/ops\/fulfillment\/carriers/, "fulfillment:logistics:read"],
  ["*", /^\/ops\/fulfillment\/carriers/, "fulfillment:rule:update"],
  ["GET", /^\/ops\/(shipments|freight-templates)/, "fulfillment:logistics:read"],
  ["*", /^\/ops\/(shipments|freight-templates)/, "fulfillment:rule:update"],

  // ── 增长与归因（P-9，V121）──────────────────────────────────────────────
  // 读写分开的理由写在 Perms.GROWTH_ATTRIBUTION_READ 上：BD 要查得到链路（商家质疑账单），
  // 但改优先级 = 改一批商家的佣金档（ADR-004 §6）
  ["GET", /^\/ops\/attribution-(rule|traces)/, "growth:attribution:read"],
  ["*", /^\/ops\/attribution-rule/, "growth:attribution:update"],
  ["GET", /^\/ops\/fission-campaigns/, "growth:fission:read"],
  ["*", /^\/ops\/fission-campaigns/, "growth:fission:update"],

  // ── 风控（P-16.2，V120）─────────────────────────────────────────────────
  // 读写分开的理由写在 Perms.RISK_EVENT_READ 上：客服要看得到「这个人是不是被标记过」
  // 才解释得了一次拦截，但处置只能是风控的事。合成一个码，两头都不对。
  ["POST", /^\/ops\/risk-events\/[^/]+\/decide$/, "risk:event:handle"],
  ["GET", /^\/ops\/risk-events/, "risk:event:read"],
  // 申诉裁决与拉黑同码：两者都在改「这个人能不能下单」，分开会让配的人漏配其中一半
  ["POST", /^\/ops\/blacklists/, "risk:blacklist:update"],
  ["GET", /^\/ops\/blacklists/, "risk:blacklist:read"],
  ["GET", /^\/ops\/risk-rules/, "risk:rule:read"],
  ["*", /^\/ops\/risk-rules/, "risk:rule:update"],

  // ── 营销 ───────────────────────────────────────────────────────────────
  ["POST", /^\/ops\/coupons\/[^/]+\/issue$/, "marketing:coupon:issue"],
  ["GET", /^\/ops\/(coupons|coupon-issues)/, "marketing:coupon:read"],
  ["*", /^\/ops\/coupons/, "marketing:coupon:update"],
  ["GET", /^\/ops\/campaigns/, "marketing:campaign:read"],
  ["*", /^\/ops\/campaigns/, "marketing:campaign:update"],

  // ── 团购与求团 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/groups/, "group:campaign:read"],
  ["*", /^\/ops\/groups/, "group:campaign:audit"],
  ["POST", /^\/ops\/quotes\/[^/]+\/(price|breach)$/, "group:demand:assign",
    "改价与判毁约都会写进商家信用档案，与只读需求单池分开"],
  ["GET", /^\/ops\/quotes/, "group:demand:read"],

  // ── 结算与资金 ─────────────────────────────────────────────────────────
  ["POST", /^\/ops\/payables\/[^/]+\/paid$/, "finance:payout:execute",
    "登记付款是财务在网银付款的依据 —— 与「看结算单」同码是内控问题，不是粒度问题"],
  ["POST", /^\/ops\/payables\/[^/]+\/no-invoice$/, "finance:invoice:verify",
    "标记无票 = 接受这笔支出不能税前列支，是税务判断"],
  ["POST", /^\/ops\/payables\/[^/]+\/confirm$/, "finance:settle:execute"],
  ["GET", /^\/ops\/(settlements|split-records|payables)/, "finance:settle:read"],
  ["GET", /^\/ops\/(purchase-invoices|finance\/invoice-title)/, "finance:invoice:read"],
  ["*", /^\/ops\/(purchase-invoices|finance\/invoice-title)/, "finance:invoice:verify"],
  // 开票申请（**销项**，平台开给消费者）。与上面的 purchase-invoices（进项）
  // 复用同一对权限码 —— 经办的是同一批财务人员；但**不能合并成一条规则**：
  // 两者方向相反，将来若要分权（比如销项交给客服代办），得先分得开
  ["GET", /^\/ops\/invoice-requests/, "finance:invoice:read"],
  ["*", /^\/ops\/invoice-requests/, "finance:invoice:verify"],
  /*
   * 提现审批（P-12.2.1）。**全表第三处刻意不拆读写**（前两处是 store:page:audit
   * 与 fulfillment:batch:read）：提现队列的「读」就是审批动作的一半，
   * 没有「只看提现不审提现」的岗位 —— 拆出的只读码不会有任何角色单独持有。
   *
   * ⚠️ 持有它**不等于能打款**：通过后落 APPROVED，出款是线下动作（B-12.5）。
   */
  ["*", /^\/ops\/finance\/withdrawals/, "finance:withdraw:approve"],
  /*
   * 商家结算发票（P-12.2.4）。**第三个方向的票**：进项是供应商开给平台
   * （purchase-invoices），销项对 C 是平台开给消费者（invoice-requests），
   * 这一条是平台开给商家。复用同一对码（经办的是同一批财务），
   * 但同样单列一条规则 —— 三者方向不同，将来要分权得先分得开。
   *
   * 个税规则跟着发票走：开票与代扣是同一个问题的两半（对外的凭证、对内的扣除），
   * 而它们在 ops-web 上就是同一个 tab。
   */
  ["GET", /^\/ops\/finance\/(invoices|tax-rule)/, "finance:invoice:read"],
  ["*", /^\/ops\/finance\/(invoices|tax-rule)/, "finance:invoice:verify"],
  // 退款回退分账（P-12.1.5 / E4）。**归结算不归售后**：这个动作动的是分账
  // （把钱从商家账户收回），不是裁决 —— 裁决在 /ops/after-sales 由售后组做
  ["GET", /^\/ops\/refund-split-backs/, "finance:settle:read"],
  ["*", /^\/ops\/refund-split-backs/, "finance:settle:execute"],
  // 关单策略（P-4.2.3）。**读写必须分开**：这个数与掉单直接因果 ——
  // 调短了会把正在付款的人关掉，而客服、数据这类角色需要看得到「现在配的是多久」
  // 才能判断一次投诉是不是撞上了它。给他们只读，不给写。
  ["GET", /^\/ops\/payments\/close-rule$/, "order:order:read"],
  ["PUT", /^\/ops\/payments\/close-rule$/, "order:order:modify"],
  // 支付对账差异（并发会话 2026-08-12 新增）。**这条是守卫抓出来的** ——
  // 它们刚落进 settle:manage，矩阵当场报 FINANCE「多出来 4 条」。
  // 处理差异会改账，与只读覆盖率分开
  ["GET", /^\/ops\/payments\/recon/, "finance:recon:read"],
  ["*", /^\/ops\/payments\/recon/, "finance:recon:resolve"],
  ["GET", /^\/ops\/settle\/fee-rules/, "finance:rate:read"],
  ["*", /^\/ops\/settle\/fee-rules/, "finance:rate:update"],

  // ── 评价 ───────────────────────────────────────────────────────────────
  ["GET", /^\/ops\/review-score-config/, "review:score:read"],
  ["POST", /^\/ops\/review-score-config/, "review:score:update"],
  ["GET", /^\/ops\/(reviews|review-appeals)/, "review:review:read"],
  ["*", /^\/ops\/(reviews|review-appeals)/, "review:review:audit"],

  // ── 消息与客服 ─────────────────────────────────────────────────────────
  // 发送记录与测试发送**复用模板的两个码**：维护消息模板的与看发送记录的是同一批人，
  // 多一个码只增加配置负担。测试发送归 update 而不是 read —— 它真的会发出去。
  ["GET", /^\/ops\/notify-logs$/, "message:template:read"],
  ["*", /^\/ops\/notify-logs/, "message:template:update"],
  // 通道体检：只回「配了没有」（envVar + present），从不回密钥本身，所以是读权限。
  // 改微信模板号是**写**：两端不同值时一条也发不出去，等同于关掉这条通道
  ["GET", /^\/ops\/notify-channels/, "message:template:read"],
  ["*", /^\/ops\/notify-channels/, "message:template:update"],
  // 运营自己的收件箱：免鉴权（理由见 gen-perm-endpoint-matrix.mjs 的 PUBLIC）。
  // 这里仍要有归属，否则权限码细化时它会被落下而没人知道
  ["*", /^\/ops\/message/, "message:template:read"],
  // 站内信的**平台侧**记录（发送记录页第二个 tab）——与上一条不是一回事：
  // 那个是「我的收件箱」，这个是运营在查「平台发给谁了」
  ["GET", /^\/ops\/inapp-messages/, "message:template:read"],
  ["GET", /^\/ops\/(msg-templates|notify-quota)/, "message:template:read"],
  ["*", /^\/ops\/(msg-templates|notify-quota)/, "message:template:update"],
  // 营销广播推送任务（N6）：管触达模板/渠道的同一批运营发广播
  ["GET", /^\/ops\/push-tasks/, "message:template:read"],
  ["*", /^\/ops\/push-tasks/, "message:template:update"],
  // 场景×通道触达配置（哪个事件走哪些通道，运营可配 —— 设计：多渠道推送与运营端触达配置）
  ["GET", /^\/ops\/scene-channel/, "message:template:read"],
  ["*", /^\/ops\/scene-channel/, "message:template:update"],
  ["GET", /^\/ops\/tickets/, "message:ticket:read"],
  ["*", /^\/ops\/tickets/, "message:ticket:handle"],

  // ── 社区与网点 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/pickups/, "community:pickup:read"],
  ["*", /^\/ops\/pickups/, "community:pickup:update"],
  ["GET", /^\/ops\/communities/, "community:community:read"],
  ["*", /^\/ops\/communities/, "community:community:update"],
  ["GET", /^\/ops\/regions/, "community:region:read",
    "行政区划是主数据，读它是选覆盖社区的前置 —— 与「改社区」分开"],
  ["*", /^\/ops\/regions/, "community:region:update",
    "裁决商家补录的村：通过一条会让它对全平台商家可见，一个错别字污染的是共享的那棵树 —— "
    + "与「读区划」（几乎人人有）的出错后果不在一个量级"],

  // ── 内容与素材 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/materials/, "content:material:read"],
  ["*", /^\/ops\/materials/, "content:material:update"],
  // 兜底那条是 `*`，所以每个子资源的 GET 都要在它之前显式列出来 ——
  // 第一版漏了 questions / rankings，两条读落进了 update 码
  ["GET", /^\/ops\/contents\//, "content:material:read"],
  ["POST", /^\/ops\/contents\/posts/, "content:material:audit"],
  ["*", /^\/ops\/contents\//, "content:material:update"],

  // ── 员工与权限 ─────────────────────────────────────────────────────────
  ["GET", /^\/ops\/perm\//, "iam:role:read"],
  ["*", /^\/ops\/perm\//, "iam:role:grant"],
  ["GET", /^\/ops\/staffs/, "iam:staff:read"],
  ["*", /^\/ops\/staffs/, "iam:staff:update"],
  ["GET", /^\/ops\/audit-log/, "iam:audit:read"],

  // ── 平台配置与主数据 ───────────────────────────────────────────────────
  ["GET", /^\/ops\/(industries|service-scopes)/, "system:industry:read"],
  ["*", /^\/ops\/(industries|service-scopes)/, "system:industry:update",
    "改的是「哪些行业能开小微」，改错一批商家进件被拒 —— 与外观配置不是一回事"],
  ["GET", /^\/ops\/(appearance|rule-texts)/, "system:theme:read"],
  ["*", /^\/ops\/(appearance|rule-texts)/, "system:theme:update"],
  ["GET", /^\/ops\/(feature-flags|markets)/, "system:param:read"],
  ["*", /^\/ops\/(feature-flags|markets)/, "system:param:update"],
];

/**
 * 后端有能力、ops-web 没有任何菜单入口的端点。
 *
 * **不是缺陷登记，是事实登记** —— 这些能力今天只能由超管通过接口用。
 * 放在这里是为了让「平台端没有入口」这件事有个地方能读到，
 * 而不是等某天有人问「保证金到底在哪儿加」。
 */
export const NO_UI_PREFIXES = [
  "/ops/admission/",              // 保证金、支付额度、准入策略
  "/ops/purchase-invoices",       // 进项发票核验
  "/ops/finance/invoice-title",   // 发票抬头
  "/ops/industries",              // 行业开关（能否开小微、是否强制积分）
  "/ops/service-scopes",          // 服务范围开关
  "/ops/notify-quota",            // 推送额度
  "/ops/stores/{storeNo}/business-mode", // 经营模式切换
  "/ops/merchants/{merchantNo}/store-modes",
  // 资金路径（轴②：钱先进谁的账户）。与经营模式同一批人在配，共用同一组码 ——
  // 两者都决定钱怎么走，不该分成两套权限
  "/ops/merchants/{merchantNo}/funds-mode",
  "/ops/merchants/mode-risk",
];

/**
 * **界面功能没有独立端点时，映到覆盖它的那个码。**
 *
 * 这张表必须与 `ops-web/lib/perm-map.ts` 里的非恒等项逐条相同 ——
 * 它决定两件事：库里那个功能点的 `perm_code`（菜单灰不灰）、
 * 以及 `can()` 判不判得过（按钮显不显示）。
 *
 * **两边各写一套的代价当场见到过**：第一版只有 perm-map 写了，
 * 迁移按「ui 码不在 68 个细码里 → NULL + NOT_IMPLEMENTED」处理，
 * 于是「支付流水核对」在菜单上灰着、而 `can()` 判它可用 ——
 * 浏览器点开权限树的第一屏就看见了。
 *
 * 守卫：`packages/shared/tests/ops-perm-matrix.test.ts` 逐条比对。
 */
export const NEAREST_CODE = {
  "order:pay:read": "order:order:read",          // 支付流水核对：读的就是订单
  "order:pay:repair": "order:order:modify",      // 掉单补偿 / 关单策略：走订单干预
  "product:stock:update": "product:sku:audit",   // 预售额度与超卖：改的是商品
  "category:manage": "product:category:update",  // 历史遗留的 ACTION 码
  // 「开关与灰度」的写码。UI 沿用 env:switch（CRITICAL_PERMS 与角色矩阵里都用着，
  // 改名要动的地方比接一条映射多得多），后端真有的是 system:param:update。
  // 此前 perm-map 里它标着 UNIMPLEMENTED，于是那一页对所有人只读、开关拨不动。
  "system:env:switch": "system:param:update",
};

/** 端点 → 码；命中不了返回 null（守卫会把它报出来） */
export function codeOf(method, path) {
  for (const [m, re, code] of RULES) {
    if ((m === "*" || m === method) && re.test(path)) return code;
  }
  return null;
}
