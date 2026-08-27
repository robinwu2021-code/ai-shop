package ai.neargo.shop.auth;

import java.util.List;
import java.util.Map;

/**
 * 运营端权限码与角色定义。
 *
 * <p>放在 auth 而不是 platform 域：权限码是**授权词表**，各域的运营面都要引用它
 * （商品审核在 product、订单查询在 trade）。留在 platform 域的话，这两个域为了写一个
 * 常量就得依赖整个平台域——S7 拆 OpsController 时这一点立刻暴露出来。
 *
 * <h2>2026-08-12：从 16 个粗码细化到 68 个</h2>
 *
 * <p>原来 16 个码盖 154 个端点，粗到<b>「只读商家档案」与「封禁商家」同一个权限</b>、
 * 「看结算单」与「标记已打款」同一个权限。后者不是粒度问题，是内控问题：
 * 财务里最该分离的制单与付款共用一把钥匙。
 *
 * <p>细码不是新设计的 —— <b>ops-web 的 {@code uiPermCode} 一直维护着一份与界面功能
 * 一一对应的码表，后端从来没采纳它</b>。这次采纳，另加了 24 个「后端有能力、
 * 界面没有入口」的码（保证金、进项发票、行业开关…）。
 *
 * <p>归属关系登记在 {@code scripts/perm-endpoint-map.mjs}，
 * 由 {@code packages/shared/tests/ops-perm-matrix.test.ts} 钉住：
 * 每个端点都要有归属、不能有死规则、<b>角色×端点可访问矩阵必须与基线逐格相同</b>。
 * 最后一条是这次改造能安全做的全部理由 —— 判紧了有人报错（看得见），
 * 判松了没人报错（看不见），所以等价性必须由机器验证，不能靠人比对 154 行。
 *
 * <h2>两条贯穿全表的规矩</h2>
 *
 * <p><b>① 读写分开。</b>不是洁癖，是实测过的代价：读写合一时，只要有任何一个角色
 * 需要看而不需要改，它就会被迫多拿一份写权限，或者干脆做不了本职工作 ——
 * 合并期的 {@code community:view} 让 BD 打开审核抽屉时「覆盖小区」一个选项都没有，
 * 而通过前又强制要求选一个，<b>招商的日常流程被自己的权限卡死</b>，
 * 页面上还看不出是权限问题（列表就是空的）。
 *
 * <p><b>② 只配后端真有的码。</b>
 * （<b>2026-08-13 落地三域</b>：履约调度四个 {@code fulfillment:*} 码全部给社区运营
 * —— 矩阵 §2.3 把「履约调度」写在那个岗位名下；风控六个 {@code risk:*} 给风控；
 * 增长四个 {@code growth:*} 给活动运营，其中只读那个另给 BD。
 * 在此之前这三域后端一个端点都没有，那几个角色的清单短得不正常 —— 那是如实反映。）
 * <b>凭空映射到一个语义相近的现有码，会让权限表看着是满的而实际什么都点不动</b>；
 * 更坏的是可能顺手给出远超职责的权限（风控要封禁，就把商家治理给它，于是风控还能批入驻）。
 *
 * <p><b>角色划分对着矩阵 §2.3 的运营岗位来。没有「运营」这种大角色</b> ——
 * 一个能审商家又能改价又能退款的角色，出事时无法定位是谁的职责。
 */
public final class Perms {

    // ── 售后 ────────────────────────────────────────────────────────────────
    public static final String AFTERSALE_TICKET_READ = "aftersale:ticket:read";

    /**
     * 裁决售后（可触发退款）。
     *
     * <p><b>细化前它挂在 {@code order:view} 下</b> —— 一个「看单」的码，
     * 11 个角色里 7 个持有。也就是说风控、社区运营、活动运营都能批退款。
     * 拆出来是为了让这件事能被单独收回。
     */
    public static final String AFTERSALE_TICKET_HANDLE = "aftersale:ticket:handle";

    public static final String AFTERSALE_REFUND_READ = "aftersale:refund:read";

    /** 极速退阈值配置。同上，细化前也在 {@code order:view} 下。 */
    public static final String AFTERSALE_REFUND_APPROVE = "aftersale:refund:approve";

    // ── 社区与网点 ──────────────────────────────────────────────────────────
    public static final String COMMUNITY_READ = "community:community:read";
    public static final String COMMUNITY_UPDATE = "community:community:update";
    public static final String COMMUNITY_PICKUP_READ = "community:pickup:read";
    public static final String COMMUNITY_PICKUP_UPDATE = "community:pickup:update";

    /** 行政区划主数据。读它是「选覆盖社区」的前置，与改社区分开 —— 见类注释规矩 ①。 */
    public static final String COMMUNITY_REGION_READ = "community:region:read";

    /**
     * 裁决商家补录的村级区划。
     *
     * <p><b>与 read 分开</b>：读区划是所有运营挑覆盖范围时的前置（几乎人人有），
     * 而通过一条补录会让它对**全平台商家**可见 —— 一个错别字污染的是共享的那棵树。
     * 两者的出错后果不在一个量级。
     */
    public static final String COMMUNITY_REGION_UPDATE = "community:region:update";

    // ── 内容与素材 ──────────────────────────────────────────────────────────
    public static final String CONTENT_MATERIAL_READ = "content:material:read";

    /**
     * 种草内容审核。<b>不复用评价裁决的码</b>：那个给客服（裁决差评要看聊天记录与订单），
     * 内容审核在矩阵里归审核员，且它还管着榜单与素材 —— <b>客服不该能改首页榜单</b>。
     */
    public static final String CONTENT_MATERIAL_AUDIT = "content:material:audit";
    public static final String CONTENT_MATERIAL_UPDATE = "content:material:update";

    // ── 经营看板 ────────────────────────────────────────────────────────────
    public static final String DASHBOARD_OVERVIEW_READ = "dashboard:overview:read";

    // ── 履约调度与物流（P-5.1 / P-5.2）──────────────────────────────────────

    /**
     * 到货批次与跨自提点分拣汇总。
     *
     * <p><b>批次推进（发车/到货/签收）故意共用这个码</b>，是全表第二处不拆读写的地方
     * （第一处是店招审核 {@code STORE_PAGE_AUDIT}）。理由与那里相同：
     * 「看批次」与「发车」是同一个人同一次动作的两半，拆出来会得到一个
     * 只有社区运营用、且他必然同时持有的码 —— 那种码只增加配置负担。
     *
     * <p>还有一条更硬的理由：<b>ops-web 的发车按钮就是用这个码门控的</b>
     * （{@code app/fulfillment/page.tsx} 的 {@code canDispatch}）。
     * 后端另判一个写码，等于造一个「看得见、点下去 403」的按钮。
     */
    public static final String FULFILLMENT_BATCH_READ = "fulfillment:batch:read";

    /** 核销监控与逾期看板。<b>只读</b> —— 平台不核销（核销要扫码、要在现场、要按点收敛）。 */
    public static final String FULFILLMENT_REDEEM_READ = "fulfillment:redeem:read";

    /** 快递运单、运费模板、运力档案的只读面。 */
    public static final String FULFILLMENT_LOGISTICS_READ = "fulfillment:logistics:read";

    /**
     * 履约与物流的**规则写**：逾期处置、运费模板与超区、运力启停、换运单号。
     *
     * <p>四件事共用一个码是 ops-web 的既有口径（三个 tab 的 {@code canEdit} 都是它）。
     * 它们的共同点是「配错了订单发不出去 / 客诉」，而读它们的人（客服、财务）
     * 都不该能改 —— 该分的那一刀已经分在读写之间了。
     */
    public static final String FULFILLMENT_RULE_UPDATE = "fulfillment:rule:update";

    // ── 结算与资金 ──────────────────────────────────────────────────────────
    public static final String FINANCE_SETTLE_READ = "finance:settle:read";

    /** 对账确认（制单）。与下面的付款分开 —— 这是这次细化最主要的收益之一。 */
    public static final String FINANCE_SETTLE_EXECUTE = "finance:settle:execute";

    /**
     * <b>登记付款。</b>它不划转资金，但它是财务在网银付款的依据。
     *
     * <p>细化前它与「看结算单」同码。制单与付款不分离是内控问题，不是粒度问题 ——
     * 一个人既能确认这笔该付、又能标记已付，中间没有第二双眼睛。
     */
    public static final String FINANCE_PAYOUT_EXECUTE = "finance:payout:execute";

    public static final String FINANCE_INVOICE_READ = "finance:invoice:read";

    /** 进项发票核验与标记无票。标记无票 = 接受这笔支出不能税前列支，是税务判断。 */
    public static final String FINANCE_INVOICE_VERIFY = "finance:invoice:verify";

    public static final String FINANCE_RATE_READ = "finance:rate:read";
    public static final String FINANCE_RATE_UPDATE = "finance:rate:update";
    public static final String FINANCE_RECON_READ = "finance:recon:read";

    /** 处理支付对账差异 —— 会改账，与只读覆盖率分开。 */
    public static final String FINANCE_RECON_RESOLVE = "finance:recon:resolve";

    /**
     * 提现审批（P-12.2.1）。<b>这是运营端唯一会把钱批出去的动作</b>。
     *
     * <p><b>刻意不拆读写</b>（与 {@link #STORE_PAGE_AUDIT} 同一个例外理由）：
     * 提现队列的「读」就是审批动作的一半，没有「只看提现不审提现」的岗位。
     * 拆出的只读码会是一个没有任何角色单独持有的码 —— 那种码只增加配置负担。
     *
     * <p>⚠️ 持有它<b>不等于能打款</b>：通过后落 APPROVED，实际出款是线下动作
     * （待完成功能清单 B-12.5「一期只记账、线下结算」）。
     */
    public static final String FINANCE_WITHDRAW_APPROVE = "finance:withdraw:approve";

    // ── 团购与求团 ──────────────────────────────────────────────────────────
    public static final String GROUP_CAMPAIGN_READ = "group:campaign:read";

    /**
     * 中止违规拼团。这类「平台兜底干预」的权限容易被排在后面，因为正常流程不需要它 ——
     * 但它恰恰是<b>出事时唯一的手段</b>，而出事是迟早的。
     */
    public static final String GROUP_CAMPAIGN_AUDIT = "group:campaign:audit";

    public static final String GROUP_DEMAND_READ = "group:demand:read";

    /**
     * 平台改价与判定毁约。<b>不给客服</b>：判毁约会写进商家信用档案、影响后续准入，
     * 那是招商侧要承担后果的判断，不是接一通电话就能下的结论。
     */
    public static final String GROUP_DEMAND_ASSIGN = "group:demand:assign";

    // ── 员工与权限 ──────────────────────────────────────────────────────────
    public static final String IAM_STAFF_READ = "iam:staff:read";
    public static final String IAM_STAFF_UPDATE = "iam:staff:update";
    public static final String IAM_ROLE_READ = "iam:role:read";

    /** 改角色与角色的功能点。**能改它的人能给自己提权** —— 与只读员工列表必须分开。 */
    public static final String IAM_ROLE_GRANT = "iam:role:grant";

    public static final String IAM_AUDIT_READ = "iam:audit:read";

    // ── 营销 ────────────────────────────────────────────────────────────────
    public static final String MARKETING_COUPON_READ = "marketing:coupon:read";
    public static final String MARKETING_COUPON_UPDATE = "marketing:coupon:update";

    /** 手工发券 —— 直接产生对用户的负债，与改券模板分开。 */
    public static final String MARKETING_COUPON_ISSUE = "marketing:coupon:issue";

    public static final String MARKETING_CAMPAIGN_READ = "marketing:campaign:read";
    public static final String MARKETING_CAMPAIGN_UPDATE = "marketing:campaign:update";

    // ── 会员与人档（P8）─────────────────────────────────────────────────────
    /**
     * 跨商家看会员名单与归属。
     *
     * <p><b>看得到人，但看不到完整手机号</b> —— 运营端一律只给后四位。
     * 需要完整号的场景只有申诉处置，那条路要单独的权限码 + 二次确认 + 留痕。
     */
    public static final String MEMBER_MEMBER_READ = "member:member:read";

    /** 人档：名下有哪些会员关系、绑了哪个账号、合并过什么 */
    public static final String MEMBER_PERSON_READ = "member:person:read";

    /**
     * 人工合并人档。<b>不可逆</b>：合并之后两份会员关系归到一起，拆不回来。
     * 与只读分开，因为它会改变「这个人是谁」这件事本身。
     */
    public static final String MEMBER_PERSON_MERGE = "member:person:merge";

    /**
     * 查看完整手机号（申诉处置）。
     *
     * <p><b>单独一个码，且每次调用都写审计</b>：这是整个系统里唯一能把
     * 「后四位」还原成一个真实号码的地方。谁在什么时候看了谁的号，必须留得下来。
     */
    public static final String MEMBER_PHONE_REVEAL = "member:phone:reveal";

    // ── 商家治理 ────────────────────────────────────────────────────────────
    public static final String MERCHANT_APPLY_AUDIT = "merchant:apply:audit";
    public static final String MERCHANT_READ = "merchant:merchant:read";

    /**
     * 违规处置与封禁（含归档）。
     *
     * <p><b>细化前它与「只读商家档案」是同一个码</b>，也就是「能查商家」等于「能封店」。
     * ops-web 那份码表早就把两者分开了，后端分不开 —— 这次改造的起点就是这一条。
     */
    public static final String MERCHANT_BAN = "merchant:merchant:ban";

    public static final String MERCHANT_CATEGORY_READ = "merchant:category:read";

    /**
     * 给某个商家授资质。类目上挂着 {@code required_code}，授资质等于放行一整类商品的准入。
     *
     * <p><b>与 {@link #PRODUCT_CATEGORY_UPDATE} 分开</b>：那个改的是「世界上有哪些资质码」，
     * 这个是「给这家店授哪几个」。第一版把两者归成一个码，自检发现它横跨了两个旧粗码 ——
     * 机械展开时会让只有其中一个的角色白拿另一半。
     */
    public static final String MERCHANT_CATEGORY_GRANT = "merchant:category:grant";

    public static final String MERCHANT_VERIFY_GRANT = "merchant:verify:grant";
    public static final String MERCHANT_MODE_READ = "merchant:mode:read";

    /** 门店经营模式切换。<b>细化前挂在结算下</b> —— 它根本不是结算。 */
    public static final String MERCHANT_MODE_UPDATE = "merchant:mode:update";

    public static final String MERCHANT_ADMISSION_READ = "merchant:admission:read";

    /** 保证金流水、支付额度、准入策略。**没有界面入口**，今天只能走接口。 */
    public static final String MERCHANT_ADMISSION_UPDATE = "merchant:admission:update";

    /** 锁路/解锁某门店的一条送货方式（方案 v4 §7.3）。投诉处置用，商家配置原样保留 */
    public static final String MERCHANT_FULFILLMENT_UPDATE = "merchant:fulfillment:update";

    // ── 消息与客服 ──────────────────────────────────────────────────────────
    public static final String MESSAGE_TEMPLATE_READ = "message:template:read";
    public static final String MESSAGE_TEMPLATE_UPDATE = "message:template:update";
    public static final String MESSAGE_TICKET_READ = "message:ticket:read";

    /**
     * 回复、分派、关闭客服工单。<b>与看工单分开</b>：工单的回复内容会直接发给用户、
     * 署的是平台的名 —— 写错一句和看错一单，代价不在一个量级。
     */
    public static final String MESSAGE_TICKET_HANDLE = "message:ticket:handle";

    // ── 交易订单 ────────────────────────────────────────────────────────────
    public static final String ORDER_READ = "order:order:read";

    /**
     * 人工干预订单状态。<b>与看单分开</b>：看单客服天天做，改单是把系统的判断覆盖掉。
     * 给看单权限时顺手给了改单，等于每个客服都能把任意订单改成已完成。
     */
    public static final String ORDER_MODIFY = "order:order:modify";

    /** 代客下单与代客取消。 */
    public static final String ORDER_PROXY = "order:order:proxy";

    // ── 商品与类目 ──────────────────────────────────────────────────────────
    public static final String PRODUCT_SKU_READ = "product:sku:read";
    public static final String PRODUCT_SKU_AUDIT = "product:sku:audit";
    public static final String PRODUCT_CATEGORY_READ = "product:category:read";

    /**
     * 标准品库（TDD-标准品库）。
     *
     * <p><b>与类目分开而不是复用 {@code product:category:*}</b>：类目决定「这类货要什么资质」，
     * 标准品决定「这件货长什么样」。前者是准入门槛，后者是录入模板 ——
     * 让能改准入的人才能录标准品，会把一件运营日常挡在一个很高的门后面。
     */
    public static final String PRODUCT_STD_READ = "product:std:read";
    public static final String PRODUCT_STD_UPDATE = "product:std:update";

    /**
     * 规格库（维度 / 值 / 类目绑定）。
     *
     * <p><b>与类目分开而不是复用 {@code product:category:*}</b>：类目权限还兼着
     * 资质门槛（{@code required_code} 决定一整类商品的准入），而规格库改一条
     * <b>会影响所有商家的建品页</b>——两件事的授权范围不该绑在一起：
     * 配规格的人不必有权放宽准入，改准入的人也不必天天进规格库。
     */
    public static final String PRODUCT_SPEC_READ = "product:spec:read";
    public static final String PRODUCT_SPEC_UPDATE = "product:spec:update";

    /**
     * 主题分类（陈列）。
     *
     * <p><b>与类目、标准品都分开</b>：类目是准入门槛、标准品是录入模板，
     * 而主题只是「这周首页摆什么」—— 它是改动最频繁、后果最轻的一档，
     * 挂在类目那个高门槛下面等于让一件运营日常天天找人开权限。
     */
    public static final String PRODUCT_TOPIC_READ = "product:topic:read";
    public static final String PRODUCT_TOPIC_UPDATE = "product:topic:update";

    /**
     * 类目树与资质码字典的维护。
     *
     * <p>与审单件商品分开：类目上挂着 {@code required_code}（经营准入的判据），
     * 改它等于放宽或收紧一整类商品的准入门槛，影响面比审一件商品大一个量级。
     */
    public static final String PRODUCT_CATEGORY_UPDATE = "product:category:update";

    // ── 评价 ────────────────────────────────────────────────────────────────
    public static final String REVIEW_READ = "review:review:read";

    /**
     * 评价裁决与差评申诉。给客服 —— 裁决要看聊天记录与订单，那是客服每天在做的事。
     * 不给商品运营：他审的是商品能不能上架，而裁决差评影响的是<b>一家店的公开评分</b>。
     */
    public static final String REVIEW_AUDIT = "review:review:audit";

    public static final String REVIEW_SCORE_READ = "review:score:read";
    public static final String REVIEW_SCORE_UPDATE = "review:score:update";

    // ── 门店主页 ────────────────────────────────────────────────────────────
    /**
     * 店招公告审核。<b>整个门店主页域曾被记成「后端零实现」，而这两条一直在跑</b> ——
     * 那份三方对齐 review 的这一条是错的。
     *
     * <p>这里故意不拆读写（全表唯一的例外）：待审队列的「读」就是审核动作的一半，
     * 拆出来会得到一个只有审核员用、且必然同时持有的码。
     */
    public static final String STORE_PAGE_AUDIT = "store:page:audit";

    // ── 风控（P-16.2）────────────────────────────────────────────────────────
    /**
     * 风险事件（刷单 / 异常裂变 / 恶意退款）。
     *
     * <p><b>读写分开</b>：客服要看得到「这个用户是不是被风控标记过」才能解释一次拦截，
     * 但处置（确认/误判）只能由风控做 —— 合成一个码，要么客服处置得了，
     * 要么他连解释都做不到。
     */
    public static final String RISK_EVENT_READ = "risk:event:read";
    public static final String RISK_EVENT_HANDLE = "risk:event:handle";

    /** 黑名单与申诉。拉黑直接挡住一个人下单，与只读分开 */
    public static final String RISK_BLACKLIST_READ = "risk:blacklist:read";
    public static final String RISK_BLACKLIST_UPDATE = "risk:blacklist:update";

    /**
     * 拦截规则配置。<b>规则是全平台生效的</b> ——
     * 改一个阈值可能一次拦掉一批正常用户，所以写权限只给风控。
     */
    public static final String RISK_RULE_READ = "risk:rule:read";
    public static final String RISK_RULE_UPDATE = "risk:rule:update";

    // ── 增长与归因（P-9，V121）───────────────────────────────────────────────
    /**
     * 归因规则与链路审计。
     *
     * <p><b>读写必须分开</b>：商家质疑账单时，BD 与客服要查得到归因链路（读），
     * 但**改优先级等于改一批商家的佣金档**（ADR-004 §6）—— 那只能是增长运营的事。
     * 合成一个码，要么 BD 答不了商家的问题，要么他能顺手改掉全平台的费率归属。
     */
    public static final String GROWTH_ATTRIBUTION_READ = "growth:attribution:read";
    public static final String GROWTH_ATTRIBUTION_UPDATE = "growth:attribution:update";

    /** 裂变活动（邀请有礼 / 老带新）。奖励只能是券（ADR-004：不用现金买增长） */
    public static final String GROWTH_FISSION_READ = "growth:fission:read";
    public static final String GROWTH_FISSION_UPDATE = "growth:fission:update";

    // ── 平台配置与主数据 ────────────────────────────────────────────────────
    public static final String SYSTEM_INDUSTRY_READ = "system:industry:read";

    /**
     * 行业与服务范围开关。<b>与外观/参数配置分开</b>：它改的是「哪些行业能开小微」，
     * 改错的后果是一批商家进件被拒 —— 那是通道规则的落点，不是招商能自行判断的事。
     */
    public static final String SYSTEM_INDUSTRY_UPDATE = "system:industry:update";

    public static final String SYSTEM_THEME_READ = "system:theme:read";

    /** 皮肤与规则文案 —— 下发给 C 端的东西。 */
    public static final String SYSTEM_THEME_UPDATE = "system:theme:update";

    public static final String SYSTEM_PARAM_READ = "system:param:read";

    /**
     * 功能开关、灰度、市场与汇率。改的是<b>全平台的行为</b> ——
     * 汇率错一位、灰度开关拨错一档，是所有人立刻受影响。
     */
    public static final String SYSTEM_PARAM_UPDATE = "system:param:update";

    /** 存储空间统计与待回收清单，只读。 */
    public static final String SYSTEM_MEDIA_READ = "system:media:read";

    /**
     * 发起图片回收任务 —— <b>删文件，不可逆</b>。
     *
     * <p>与 {@link #SYSTEM_MEDIA_READ} 分成两个码，因为「能看」和「能删」通常不是同一个人：
     * 看清单是日常巡检，删是一次性的破坏操作。合成一个码的话，
     * 任何一个来看看占了多少空间的人手里都握着删库的按钮。
     */
    public static final String SYSTEM_MEDIA_PURGE = "system:media:purge";

    /**
     * 定时任务的**只读**面：看任务清单、看上次跑成没有、翻执行日志。
     *
     * <p><b>与「能关掉任务」分开，而且这一条最该分开</b>：
     * 排查问题的人比能停任务的人多得多 —— 一个任务出事时，
     * 先来看的往往是被它影响到的那条业务线的人，而他们不该有权把它停掉。
     */
    public static final String SYSTEM_JOB_READ = "system:job:read";

    /**
     * 开关、改 cron、立即执行。**每一样都当场改变系统的行为**：
     * 关掉关单任务，库存就从那一刻起不再释放。所以它不与只读同码。
     */
    public static final String SYSTEM_JOB_MANAGE = "system:job:manage";

    /**
     * 角色 → 权限码。**对着矩阵 §2.3 的十一个岗位逐条配**。
     *
     * <p><b>本表 2026-08-12 先从 16 个粗码机械展开（阶段 A，等价，零行为变化），
     * 随后收紧了三处（阶段 B）</b>：
     *
     * <ol>
     *   <li>{@link #AFTERSALE_TICKET_HANDLE} 与 {@link #AFTERSALE_REFUND_APPROVE}
     *       原本 7 个角色都有（从 {@code order:view} 带过来的）—— 也就是风控、社区运营、
     *       活动运营都能批退款。收回到客服（裁决是它的本职）与财务（只保留极速退阈值，
     *       它承担资金后果）。<b>读没有一起收</b>：查售后单是排查恶意退款的最低限度，
     *       风险与写不在一个量级 —— 一次收两样会让人分不清是哪一样出的问题。</li>
     *   <li>{@link #MERCHANT_MODE_UPDATE} 从财务挪到 BD —— 经营模式是商家线的事，
     *       它此前落在财务手里只是因为挂在结算的粗码下。</li>
     *   <li>{@link #MERCHANT_BAN} 无需改配置：它只在 BD 手里，而商家治理本来就归 BD。
     *       细化本身就是收益 —— 从今天起它<b>能</b>被单独收回。</li>
     * </ol>
     *
     * <p><b>还有一条改配置解决不了</b>：{@link #FINANCE_SETTLE_EXECUTE}（制单）与
     * {@link #FINANCE_PAYOUT_EXECUTE}（付款）现在都在 FINANCE 一个角色上。
     * 真正的职责分离需要第二个财务岗，那是产品决定，不是权限表能单方面做的。
     * 码已经分开，等那个岗位存在时改一行就行。
     *
     * <p>阶段 A 的等价性由 {@code ops-perm-matrix.test.ts} 验证过（逐格相同）；
     * 阶段 B 让那条守卫变红，红的内容就是上面这三条 —— 审过之后更新的基线。
     *
     * <p>后端角色码与 ops-web 一致；已有的三个（BD / GOODS_OPS / SUPPORT）是历史遗留，
     * 在 ops-web 的 http 层翻译。
     *
     * <p><b>这张表现在是回落表</b>：判权的主路径是
     * {@code RolePermResolver} 读库（{@code sys_role_point → sys_function_point.perm_code}）。
     * 库里查不到这个角色时才用它 —— 「没配」与「配了零权限」必须分开，
     * 后者会让一个本该有权限的岗位静默失权。
     */
    private static final Map<String, List<String>> ROLE_PERMS = Map.ofEntries(
            Map.entry("SUPER_ADMIN", List.of("*")),

            Map.entry("BD", List.of(AFTERSALE_REFUND_READ, AFTERSALE_TICKET_READ,
                    COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, GROUP_DEMAND_ASSIGN, GROUP_DEMAND_READ,
                    MERCHANT_APPLY_AUDIT, MERCHANT_CATEGORY_GRANT, MERCHANT_CATEGORY_READ,
                    MERCHANT_BAN, MERCHANT_READ, MERCHANT_MODE_READ, MERCHANT_MODE_UPDATE,
                    MERCHANT_FULFILLMENT_UPDATE,
                    MERCHANT_VERIFY_GRANT, ORDER_READ, STORE_PAGE_AUDIT,
                    // 只读：商家质疑「这单是我带来的」时 BD 要查得到链路，
                    // 但改优先级 = 改一批商家的佣金档，那是增长运营的事
                    GROWTH_ATTRIBUTION_READ)),

            Map.entry("GOODS_OPS", List.of(AFTERSALE_REFUND_READ,
                    MEMBER_MEMBER_READ, MEMBER_PERSON_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, GROUP_CAMPAIGN_AUDIT, GROUP_CAMPAIGN_READ,
                    MARKETING_CAMPAIGN_READ, MARKETING_CAMPAIGN_UPDATE, MARKETING_COUPON_ISSUE,
                    MARKETING_COUPON_READ, MARKETING_COUPON_UPDATE, ORDER_READ,
                    PRODUCT_CATEGORY_READ, PRODUCT_CATEGORY_UPDATE, PRODUCT_SKU_AUDIT,
                    PRODUCT_SKU_READ, PRODUCT_SPEC_READ, PRODUCT_SPEC_UPDATE,
                    PRODUCT_STD_READ, PRODUCT_STD_UPDATE,
                    PRODUCT_TOPIC_READ, PRODUCT_TOPIC_UPDATE)),

            Map.entry("SUPPORT", List.of(AFTERSALE_REFUND_APPROVE, AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_HANDLE, AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    DASHBOARD_OVERVIEW_READ, MESSAGE_TEMPLATE_READ, MESSAGE_TEMPLATE_UPDATE,
                    MESSAGE_TICKET_HANDLE, MESSAGE_TICKET_READ, ORDER_MODIFY, ORDER_PROXY,
                    ORDER_READ, REVIEW_AUDIT, REVIEW_READ, REVIEW_SCORE_READ,
                    REVIEW_SCORE_UPDATE)),

            Map.entry("CAMPAIGN_OPS", List.of(AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    CONTENT_MATERIAL_AUDIT, CONTENT_MATERIAL_READ, CONTENT_MATERIAL_UPDATE,
                    DASHBOARD_OVERVIEW_READ, GROUP_CAMPAIGN_AUDIT, GROUP_CAMPAIGN_READ,
                    GROWTH_ATTRIBUTION_READ, GROWTH_ATTRIBUTION_UPDATE,
                    GROWTH_FISSION_READ, GROWTH_FISSION_UPDATE,
                    MARKETING_CAMPAIGN_READ, MARKETING_CAMPAIGN_UPDATE, MARKETING_COUPON_ISSUE,
                    MARKETING_COUPON_READ, MARKETING_COUPON_UPDATE, ORDER_READ)),

            /*
             * 社区运营。矩阵 §2.3 原话：「社区网格、自提点建档与启停、**履约调度**」——
             * 四个 fulfillment 码全给它，其余角色一个都不给。
             *
             * **刻意不给客服快递只读**：他的数据边界是「按工单授权」，
             * 而 /ops/shipments 是全平台运单。要让客服查一单物流，
             * 该做的是工单里的订单维度入口，不是把全量运单表发出去。
             */
            Map.entry("COMMUNITY_OPS", List.of(AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, COMMUNITY_READ,
                    COMMUNITY_UPDATE, COMMUNITY_PICKUP_READ, COMMUNITY_PICKUP_UPDATE,
                    COMMUNITY_REGION_READ, COMMUNITY_REGION_UPDATE, DASHBOARD_OVERVIEW_READ,
                    FULFILLMENT_BATCH_READ, FULFILLMENT_LOGISTICS_READ,
                    FULFILLMENT_REDEEM_READ, FULFILLMENT_RULE_UPDATE,
                    ORDER_READ,
                    SYSTEM_INDUSTRY_READ, SYSTEM_INDUSTRY_UPDATE)),

            Map.entry("AUDITOR", List.of(COMMUNITY_READ, CONTENT_MATERIAL_AUDIT,
                    CONTENT_MATERIAL_READ, CONTENT_MATERIAL_UPDATE, PRODUCT_SKU_AUDIT,
                    PRODUCT_SKU_READ, REVIEW_AUDIT, REVIEW_READ, REVIEW_SCORE_READ,
                    REVIEW_SCORE_UPDATE)),

            Map.entry("FINANCE", List.of(AFTERSALE_REFUND_APPROVE, AFTERSALE_REFUND_READ,
                    AFTERSALE_TICKET_READ, DASHBOARD_OVERVIEW_READ,
                    FINANCE_INVOICE_READ, FINANCE_INVOICE_VERIFY, FINANCE_PAYOUT_EXECUTE,
                    FINANCE_RATE_READ, FINANCE_RATE_UPDATE, FINANCE_RECON_READ,
                    FINANCE_RECON_RESOLVE, FINANCE_SETTLE_EXECUTE, FINANCE_SETTLE_READ,
                    // 提现审批（P-12.2.1）。财务是唯一该持有它的角色 ——
                    // 超管靠通配拿到，其余角色一律不给：这是把钱批出去的那个动作
                    FINANCE_WITHDRAW_APPROVE,
                    MERCHANT_ADMISSION_READ, MERCHANT_ADMISSION_UPDATE, ORDER_READ)),

            /*
             * 风控：矩阵给的是「刷单、异常裂变、恶意退款、黑名单」+ 拦截封禁。
             * 六个 risk:* 码随风控域落地补齐（V120）——
             * <b>在此之前这份清单短得不正常</b>：后端一个风控端点都没有，
             * 而清单里的售后裁决与极速退阈值是从 order:view 带过来的，风控本不该有。
             * 那两条现在保留，因为恶意退款画像要看得到售后单。
             */
            Map.entry("RISK", List.of(AFTERSALE_REFUND_READ, AFTERSALE_TICKET_READ,
                    DASHBOARD_OVERVIEW_READ, ORDER_READ,
                    RISK_EVENT_READ, RISK_EVENT_HANDLE, RISK_BLACKLIST_READ,
                    RISK_BLACKLIST_UPDATE, RISK_RULE_READ, RISK_RULE_UPDATE)),

            /*
             * 数据分析：矩阵写明**只读脱敏**。故意不给 ORDER_READ ——
             * 那个码返回的是完整订单（金额、联系人），与「脱敏」正相反。
             * 真要做明细分析，缺的是一个脱敏读的码，不是把全量读权限发出去。
             */
            Map.entry("ANALYST", List.of(COMMUNITY_READ)),

            /*
             * 技术运维：配置、灰度、日志、存储。环境切换仍然没有端点。
             *
             * 存储回收（SYSTEM_MEDIA_PURGE）给到这个岗位而不是更广的范围：
             * 它删的是磁盘上的文件，判断依据是「这张图还有没有人引用」——
             * 这是个工程问题，不是业务问题。业务岗位需要的是看得见占用（READ），
             * 而不是握着删除按钮。
             */
            Map.entry("TECH_OPS", List.of(IAM_AUDIT_READ, SYSTEM_PARAM_READ, SYSTEM_PARAM_UPDATE,
                    SYSTEM_THEME_READ, SYSTEM_THEME_UPDATE,
                    SYSTEM_MEDIA_READ, SYSTEM_MEDIA_PURGE,
                    // 定时任务归技术运维：出事时来看的是它，能停的也该是它。
                    // **只给 TECH_OPS 与超管** —— 停一个任务的后果是业务级的
                    // （关掉关单，库存从那一刻起不再释放），不该顺手落在别的岗位手里
                    SYSTEM_JOB_READ, SYSTEM_JOB_MANAGE)));

    private Perms() {
    }

    public static List<String> of(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .flatMap(r -> ROLE_PERMS.getOrDefault(r, List.of()).stream())
                .distinct().toList();
    }
}
