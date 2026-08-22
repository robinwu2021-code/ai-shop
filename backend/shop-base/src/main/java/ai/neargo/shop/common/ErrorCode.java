package ai.neargo.shop.common;

/**
 * 错误码（分段见 docs/api/响应格式规范.md §3，与本枚举的同步有守卫测试锁住）。段位不是装饰：C 端 http-client 按段决定
 * 「弹 toast / 跳登录 / 跳申诉页」，改段等于改前端行为。
 *
 * <p>{@code msgKey} 指向 {@code i18n/messages*.properties}，响应文案由 {@link Messages} 按
 * {@code Accept-Language} 渲染 —— 后端不出中文硬编码文案。
 */
public enum ErrorCode {

    // ---- 1xxxx 通用 ----
    BAD_REQUEST(10400, "err.bad_request"),
    UNAUTHORIZED(10401, "err.unauthorized"),
    /*
     * 令牌带了、但会话已经不在（过期或被吊销）。
     *
     * **与 10401「未登录」分开**，理由与发码限流那两条一样：端上要做的事不同。
     *   · 未登录  → 引导去登录页，这是常态（游客逛店）
     *   · 已过期  → 说「登录已过期，请重新登录」，并**清掉本地那份 token**
     *
     * 此前两者都是**空响应体的 401**，端上分不出来 —— 而 B 端联调抓到的原缺陷
     * 正是把过期说成了「没权限」：一个让人去重登，一个让人去找老板要权限，
     * 下一步动作完全相反。
     */
    TOKEN_EXPIRED(10402, "err.token_expired"),
    FORBIDDEN(10403, "err.forbidden"),
    NOT_FOUND(10404, "err.not_found"),
    CONFLICT(10409, "err.conflict"),
    TOO_MANY_REQUESTS(10429, "err.too_many_requests"),
    /** 逆地理编码没配地图厂商密钥：端上据此**藏掉**「定位取地址」按钮，而不是报错 */
    GEO_UNAVAILABLE(10503, "err.geo.unavailable"),

    /*
     * 发码限流的两条。**分成两个码而不是共用 10429**：端上要做的事完全不同 ——
     * 间隔闸能给出「还要等几秒」，端上据此做倒计时按钮；
     * 当日上限则是「今天别再试了」，显示倒计时反而是骗人。
     * 共用一个码时端上只能都说「操作太频繁，请稍后再试」，
     * 而对撞上日上限的人来说，「稍后」是错的。
     */
    OTP_TOO_FREQUENT(10450, "err.otp.too_frequent"),

    OTP_DAILY_LIMIT(10451, "err.otp.daily_limit"),

    /** 图形验证码错误或已过期。保护「测试发送」那个能指定任意收件人的接口 */
    CAPTCHA_INVALID(10452, "err.captcha.invalid"),

    /**
     * 密码重置码无效或已过期。
     *
     * <p>**与「参数有误」分开**：用户点的是邮件里的链接，参数是系统给的，
     * 说「参数有误」会让他去检查自己没填过的东西。这里要说的是「重新申请一次」。
     */
    RESET_TOKEN_INVALID(10453, "err.reset_token.invalid"),

    /**
     * 验证码连续输错太多次，暂时锁定。
     *
     * <p>**与「验证码错误」分开**：还能再试与已经锁了，用户要做的事完全不同 ——
     * 前者是再看一眼短信，后者是等，或者去换一条路。
     * 共用一个码时他会一直重试，而每一次重试都在延长锁定。
     */
    OTP_LOCKED(10454, "err.otp.locked"),

    /**
     * 验证码不对，或者已经过期。
     *
     * <p>**这条以前不存在** —— 验证码错走的是 10400「请求参数有误」，
     * 而 10400 的意思是「你传的东西不对，去检查一下参数」。用户看到的是
     * 「请求无效」这种技术腔的话，他不知道下一步该干什么。
     * 上面 {@link #OTP_LOCKED} 的注释已经写了「与『验证码错误』分开」——
     * 那句话在等的就是这个码。
     *
     * <p>模拟器上实测到的原文是英文的 "Invalid request"：后端按系统语言回英文，
     * 而页面其余文案是端上 i18n 的中文，**同一屏两种语言**。
     */
    OTP_INVALID(10455, "err.otp.invalid"),
    /**
     * 手机号或密码不对。
     *
     * <p><b>刻意不区分「查无此人」与「密码错」</b>：分开说等于给撞库的人一个
     * 免费的账号探测接口——他能用它把哪些手机号注册过筛一遍。
     * 对真用户来说这两种情况的下一步动作也一样（换个号试，或去用验证码登录）。
     */
    PASSWORD_INVALID(10456, "err.password.invalid"),
    /**
     * 这个账号<b>还没设过密码</b>。
     *
     * <p>与 10456 分开是因为下一步动作不同：这条要引导他「先用验证码登录，
     * 再去设密码」。而这条不泄露账号是否存在——只有确实存在、且确实没设过密码
     * 的人才会看到它，撞库者拿它探测不到新信息（存在但设过密码的返回 10456）。
     */
    PASSWORD_NOT_SET(10457, "err.password.not_set"),
    /**
     * 这条路**还没通**，不是参数错了。
     *
     * <p>与 10400 分开的理由：10400 让人去检查自己传了什么，而这里怎么改参数都没用。
     * 典型场景是入口先于能力落地 —— 界面上摆着四个选项，后端只实现了一个，
     * 剩下三个必须说「还没做」而不是「你填错了」。
     */
    NOT_IMPLEMENTED(10501, "err.not_implemented"),

    /*
     * 员工管理的三条闸。分成三个码而不是共用 10400：
     * 「参数不对」与「你不能对自己做这件事」是完全不同的两回事，
     * 而运营看到的提示决定他下一步怎么办。
     */
    /** 不能停用/降级自己 —— 把自己锁在门外之后只能去库里手改 */
    STAFF_SELF_OPERATION(10420, "err.staff.self_operation"),
    /** 角色码在 Perms.ROLE_PERMS 里没有配置：写进去会造出一个 perms 为空的账号 */
    STAFF_ROLE_UNKNOWN(10421, "err.staff.role_unknown"),

    /** 登录名已被占用。**与「密码错」分开**：这是建号时的校验，不涉及登录探测 */
    STAFF_USERNAME_TAKEN(10423, "err.staff.username_taken"),

    /** 新建员工的登录名必须是邮箱。存量账号（admin/bd 这类短用户名）不受影响，只对新建生效 */
    STAFF_USERNAME_NOT_EMAIL(10424, "err.staff.username_not_email"),
    /** 给全量角色配数据域：存下来会让人以为限制生效了，而实际没有 */
    STAFF_SCOPE_ON_FULL_ACCESS(10422, "err.staff.scope_on_full_access"),

    /*
     * 内容治理（P-15.2）。分成六个码而不是共用 10400：
     * 审核员看到的提示决定他下一步怎么办 —— 「批量里有风险内容」和「参数不对」
     * 要采取的行动完全不同（前者是去逐条看，后者是检查自己传了什么）。
     */
    /** 驳回/下架/隐藏没写原因。原因**原样回作者**，不写等于让人猜 */
    REASON_REQUIRED(10430, "err.content.reason_required"),
    /** 批量通过的单子里含命中风险词的内容。**整批拒绝而不是跳过** —— 静默跳过会让人以为全过了 */
    CONTENT_RISK_IN_BATCH(10431, "err.content.risk_in_batch"),
    /** 非法的状态流转。PASSED→OFFLINE 是单独一条路，不能退回待审 */
    CONTENT_BAD_TRANSITION(10432, "err.content.bad_transition"),
    /** 已回答的问题不能再答 —— 要改先隐藏，让改动本身留下痕迹 */
    CONTENT_ALREADY_ANSWERED(10433, "err.content.already_answered"),
    /** 人工榜条目数超过容量 */
    CONTENT_RANKING_OVERSIZE(10434, "err.content.ranking_oversize"),
    /** 人工榜里有不在售的商品：用户点进去是空页 */
    CONTENT_RANKING_SKU_OFFLINE(10435, "err.content.ranking_sku_offline"),
    /** 非人工榜带了条目：传了就是调用方理解错了 */
    CONTENT_RANKING_MANUAL_ONLY(10436, "err.content.ranking_manual_only"),
    /** 限定投放范围却没有投放对象：保存成功却谁都看不到 */
    CONTENT_SCOPE_REFS_REQUIRED(10437, "err.content.scope_refs_required"),

    /** 预置角色是 Perms.java 的镜像，改了会与回落表分叉 */
    PERM_BUILTIN_ROLE_READONLY(10440, "err.perm.builtin_role_readonly"),
    /** 还有人在用的角色不能删：删了他们能登录但什么都点不动，且看不出原因 */
    PERM_ROLE_IN_USE(10441, "err.perm.role_in_use"),
    /** 角色码格式不对。它是授权的键（sys_role_point / sys_role_member 都指着它），
     *  不能随便塞任意字符——大写字母开头，只能有大写字母/数字/下划线 */
    PERM_ROLE_CODE_INVALID(10442, "err.perm.role_code_invalid"),
    INTERNAL_ERROR(10500, "err.internal"),

    // ---- 2xxxx 交易 ----
    STOCK_NOT_ENOUGH(20001, "err.trade.stock_not_enough"),
    PRICE_CHANGED(20002, "err.trade.price_changed"),
    OUT_OF_DELIVERY_RANGE(20003, "err.trade.out_of_delivery_range"),
    ORDER_STATE_ILLEGAL(20004, "err.trade.order_state_illegal"),

    // ---- 3xxxx 履约 ----
    ALREADY_VERIFIED(30001, "err.fulfillment.already_verified"),
    NOT_THIS_PICKUP_POINT(30002, "err.fulfillment.not_this_pickup"),
    ORDER_REFUNDED(30003, "err.fulfillment.order_refunded"),

    /*
     * 履约调度与物流（P-5.1 / P-5.2）。**每一条都对应运营端一个不同的下一步动作** ——
     * 共用 10400「参数有误」的话，运营看到的永远是同一句话，而他要做的事完全不同：
     * 改一个数字、换一家运力、还是先去把在途单跑完。
     */

    /** 批次跳步（没发车就到货、没到货就签收）。责任判定的依据会被跳过去 */
    BATCH_TRANSITION_ILLEGAL(30004, "err.fulfillment.batch_transition_illegal"),

    /** 逾期宽限期不足 1 小时。**到点即作废必产生客诉**，宽限期是规则不是建议 */
    OVERDUE_GRACE_TOO_SHORT(30005, "err.fulfillment.overdue_grace_too_short"),

    /** 已签收的快递单不许改运单号 —— 等于把一条已完成的轨迹指向别处 */
    WAYBILL_LOCKED(30006, "err.fulfillment.waybill_locked"),

    /** 同一承运商下运单号重复 —— 会把两单的轨迹搅在一起 */
    WAYBILL_DUPLICATED(30007, "err.fulfillment.waybill_duplicated"),

    /** 默认运费模板不能归档 —— 归档之后新商家没有模板可用 */
    FREIGHT_DEFAULT_LOCKED(30008, "err.fulfillment.freight_default_locked"),

    /** 运力优先级重复 —— 同优先级时选哪家取决于查询顺序，那是隐性行为 */
    CARRIER_PRIORITY_TAKEN(30009, "err.fulfillment.carrier_priority_taken"),

    /** 没配接入密钥不能启用 —— 启用后下单当场失败，比不启用更糟 */
    CARRIER_KEY_MISSING(30010, "err.fulfillment.carrier_key_missing"),

    /** 还有在途快递单，停用后那些单的轨迹拉不回来 */
    CARRIER_HAS_IN_FLIGHT(30011, "err.fulfillment.carrier_has_in_flight"),

    /** 不能停掉最后一家启用的运力 —— 全停之后快递单无处可下 */
    CARRIER_LAST_ENABLED(30012, "err.fulfillment.carrier_last_enabled"),

    // ---- 4xxxx 营销 ----
    COUPON_SOLD_OUT(40001, "err.marketing.coupon_sold_out"),
    COUPON_NOT_APPLICABLE(40002, "err.marketing.coupon_not_applicable"),
    /** 折扣券建券/改券时必须封顶——取消「0 = 不封顶」，见 TDD-营销预算前置 */
    COUPON_DISCOUNT_CAP_REQUIRED(40003, "err.marketing.coupon_discount_cap_required"),
    /** 发行量必须 >0——不限量券的敞口同样算不出来 */
    COUPON_TOTAL_COUNT_REQUIRED(40004, "err.marketing.coupon_total_count_required"),
    /** 预算低于「发行量 × 单张最大优惠」——不接受一个从第一天起就不可能满足的预算 */
    COUPON_BUDGET_BELOW_EXPOSURE(40005, "err.marketing.coupon_budget_below_exposure"),

    /*
     * 增长与归因（P-9）。**归因规则决定商家付多少佣金**（ADR-004 §6），
     * 所以这几条不复用 BAD_REQUEST：「请求参数有误」会让运营去改别的字段，
     * 而问题在优先级表的完整性上——那是个看起来填了、其实只填了一半的框。
     */
    /** 归因优先级不是全序（三个来源有重复或有遗漏）——半张表在冲突时会随机裁决 */
    ATTRIBUTION_PRIORITY_INVALID(40006, "err.marketing.attribution_priority_invalid"),
    /** 归因窗口期越界（1–90 天）。0 天等于关掉归因，而页面上看不出来 */
    ATTRIBUTION_WINDOW_INVALID(40007, "err.marketing.attribution_window_invalid"),
    /** 新客判定一个因子都没选 = 所有人都是新客，新人券会被无限领 */
    ATTRIBUTION_FACTOR_REQUIRED(40008, "err.marketing.attribution_factor_required"),
    /** 邀请有礼只能发券（ADR-004：去团长化后不存在现金激励） */
    FISSION_REWARD_MUST_BE_COUPON(40009, "err.marketing.fission_reward_must_be_coupon"),
    /** 两边都是 0 张 = 一个不发奖的裂变活动，或者张数为负 */
    FISSION_REWARD_COUNT_INVALID(40010, "err.marketing.fission_reward_count_invalid"),

    // ---- 5xxxx 资金 ----
    SPLIT_RECEIVER_NOT_READY(50001, "err.settle.receiver_not_ready"),
    SPLIT_EXPIRED(50002, "err.settle.split_expired"),

    /**
     * 提现单状态不允许该动作（P-12.2.1）。
     *
     * <p>不复用 {@code CONFLICT}：财务看到「操作冲突」不知道该做什么，
     * 看到「这张单已经审过了」就知道该去刷新列表 —— 而重复审批一笔提现，
     * 后果是同一笔钱被批两次。
     */
    WITHDRAW_STATE_ILLEGAL(50003, "err.settle.withdraw_state_illegal"),
    /** 申请金额超过<b>申请时</b>的可提余额快照。用快照而不是实时值，见 StlWithdraw。 */
    WITHDRAW_OVER_BALANCE(50004, "err.settle.withdraw_over_balance"),
    /** 低于单笔下限 —— 渠道手续费比本金还贵。 */
    WITHDRAW_BELOW_MIN(50005, "err.settle.withdraw_below_min"),
    /** 超过复核阈值却没写复核说明。大额是最容易被冒用的口子。 */
    WITHDRAW_REVIEW_NOTE_REQUIRED(50006, "err.settle.withdraw_review_note_required"),
    /**
     * 商家处于封禁中，不放行提现。
     *
     * <p>解封是另一条链路上的决定（P-11.1.4），在这里绕过去等于让处置形同虚设 ——
     * 而处置期间恰恰是最该冻住资金的时候。单独一个码，财务才知道该去找谁解封。
     */
    WITHDRAW_MERCHANT_BANNED(50010, "err.settle.withdraw_merchant_banned"),
    /**
     * 开票金额超过该周期已结算金额。
     *
     * <p>单独一个码而不是 BAD_REQUEST：超出的那部分<b>没有真实交易对应，就是虚开</b>，
     * 而运营看到「参数有误」只会去改金额再试一次。
     */
    INVOICE_OVER_SETTLED(50007, "err.settle.invoice_over_settled"),
    /** 企业抬头缺纳税人识别号 —— 开出来对方也入不了账。 */
    INVOICE_TAX_NO_REQUIRED(50008, "err.settle.invoice_tax_no_required"),
    /** 个税税率超过硬上限（45%）。超过一定是配置错误，而它会扣光每一笔提现。 */
    TAX_RATE_TOO_HIGH(50009, "err.settle.tax_rate_too_high"),

    // ---- 6xxxx 风控 ----
    RISK_BLOCKED(60001, "err.risk.blocked"),

    /*
     * 风控处置（P-16.2）。这几条都不复用 BAD_REQUEST：运营是在一个**队列**上工作，
     * 「请求参数有误」在队列场景里最没用——他要知道的是「这单已经被同事处理了」
     * 还是「我少写了结论」，而这两件事的下一步动作完全不同。
     */
    /** 事件已被处置（多半是同事先动了手）。端上据此提示刷新列表 */
    RISK_EVENT_HANDLED(60002, "err.risk.event_handled"),
    /** 处置结论必填。**排除也要写理由**——下次同一主体再命中时，得知道上次为什么放过 */
    RISK_VERDICT_REQUIRED(60003, "err.risk.verdict_required"),
    /** 拉黑原因必填：申诉时被拉黑者要能看到自己因为什么被拉黑 */
    BLACKLIST_REASON_REQUIRED(60004, "err.risk.blacklist_reason_required"),
    /** 到期时间必填且必须在未来。无期限拉黑没有申诉出口，是产品事故不是风控严格 */
    BLACKLIST_UNTIL_REQUIRED(60005, "err.risk.blacklist_until_required"),
    /** 该对象已在生效中的黑名单里——重复拉黑会让「解禁」变成要点两次的迷题 */
    BLACKLIST_DUPLICATE(60006, "err.risk.blacklist_duplicate"),
    /** 该记录没有待裁决的申诉 */
    BLACKLIST_NO_APPEAL(60007, "err.risk.blacklist_no_appeal"),
    /** 触发阈值必须 &gt; 0——0 等于全量拦截，而页面上它只是一个普通数字 */
    RISK_THRESHOLD_INVALID(60008, "err.risk.threshold_invalid"),

    // ---- 7xxxx 商家与通道准入 ----
    /**
     * 该行业不能用这个主体类型进件（微信小微白名单按行业给，线上业态不支持）。
     *
     * <p>单独一个码而不是复用 BAD_REQUEST：端上要据此把「换个主体」这条出路
     * 直接说出来，而通用的「请求参数有误」什么也没告诉商家。
     */
    INDUSTRY_SUBJECT_NOT_ALLOWED(70001, "err.merchant.industry_subject_not_allowed"),

    /**
     * 商家没有经营该类目所需的授权（{@code prd_category.required_code} 不在
     * {@code mch_entity.category_codes} 里）。
     *
     * <p>单独一个码：端上要把**缺哪张资质**说出来并给出申请入口，
     * 通用的「请求参数有误」会让商家反复改商品信息，而问题根本不在商品上。
     */
    CATEGORY_NOT_AUTHORIZED(70002, "err.merchant.category_not_authorized"),

    /**
     * 商品未过审就想上架。
     *
     * <p>此前这里复用的是 {@link #ORDER_STATE_ILLEGAL}（「订单状态不允许该操作」）——
     * **交易域的码用在商品规则上**。商家点「上架」看到「订单状态不允许该操作」，
     * 他手上一张订单都没有，无从知道自己该等审核。
     *
     * <p>讽刺的是紧挨着的 {@link #CATEGORY_NOT_AUTHORIZED} 注释里正好写着这个道理：
     * 「通用的错误会让商家反复改商品信息，而问题根本不在商品上」——
     * 道理想明白了、用在了类目那处，漏了上一处。
     */
    GOODS_NOT_APPROVED(70003, "err.goods.not_approved"),
    /** 资质已过期，不能上架需要资质的类目。与「未获批类目」是两回事 */
    QUALIFICATION_EXPIRED(70007, "err.merchant.qualification_expired"),
    /*
     * 以下四个是弱主体（S3）的准入闸门。分成四个码而不是共用一个「准入不通过」：
     * 商家看到「被拦了」却不知道拦在哪一条，就只能猜着改，而这四条的解法完全不同 ——
     * 补钱 / 换品类 / 拆单 / 等明天。
     */
    DEPOSIT_INSUFFICIENT(70008, "err.merchant.deposit_insufficient"),
    CATEGORY_BANNED(70009, "err.merchant.category_banned"),
    ORDER_LIMIT_EXCEEDED(70010, "err.merchant.order_limit_exceeded"),
    DAILY_LIMIT_EXCEEDED(70011, "err.merchant.daily_limit_exceeded"),
    /*
     * 切第三方模式但这家店没有可用收款号。单独一个码：不校验的后果不是报错而是
     * **静默欠款** —— 订单照常成交、账单照常生成，只是钱卡在平台侧下不去，
     * 等发现时已经积了一批单。通用的「请求参数有误」说不出这件事。
     */
    PAY_MERCHANT_REQUIRED(70012, "err.merchant.pay_merchant_required"),
    /*
     * 用户选的履约方式该商品不支持。单独一个码：端上要能把「这件商品只支持到店自提」
     * 说出来 —— 通用的「请求参数有误」会让用户反复重试同一个动作。
     */
    FULFILLMENT_NOT_SUPPORTED(70013, "err.trade.fulfillment_not_supported"),
    /*
     * 快递 / 自送单缺收货地址。**单独一个码**：端上要把人送到地址簿去，
     * 而通用的「请求参数有误」只会让他在结算页上反复点提交。
     *
     * 这条闸此前不存在 —— 于是不带 addressId 的快递单能一路下成功，
     * 商家侧看到「收货人：—」，货发不出去，而全程没有任何异常
     * （2026-08-15 e2e 实测：库里 55 张快递单，有收货人的 0 张）。
     */
    RECEIVER_REQUIRED(70014, "err.trade.receiver_required"),
    /*
     * 这个权限码**不能授给自定义角色**（目前只有 `biz:store:admin` 与裸 `*`）。
     *
     * **与 70006 分开**：70006 说的是「你的角色不够」，而这里请求的人是老板，
     * 他有权建角色 —— 被拒的是那个码本身。共用一个码的表现是
     * 「店主被告知『让店主给你加个角色』」，而他就是店主。
     */
    ROLE_PERM_NOT_ASSIGNABLE(70015, "err.biz.role_perm_not_assignable"),
    /*
     * 自提单缺自提点。**与 70014「缺收货地址」是同一形状的另一半** ——
     * 送到人手上的要地址，去点上取的要点，两者都是「履约必需的信息」。
     *
     * 这条闸此前不存在，而缺了它**不会在下单时报错**：单能下、能付，
     * 之后每一步都失败且原因都指错 ——
     *   到货登记 → 空列表（像是「没有这单」）
     *   核销     → NOT_THIS_PICKUP（像是「顾客走错店了」，店员会让他去别的点，
     *              而那单根本不属于任何自提点）
     * 2026-08-17 B 端第二轮实测抓到。
     */
    PICKUP_POINT_REQUIRED(70025, "err.trade.pickup_point_required"),
    /*
     * 买家选的自提点不在这家店配置的取货点里（P1）。
     *
     * **与 70025 分开**：那条是「没选点」，这条是「选了一个店不送的点」——
     * 前者端上要弹选点器，后者要把这家店可用的点列出来让他换。
     * 店里没配过取货点（存量）不触发：空集 = 兼容期不限。
     */
    PICKUP_POINT_NOT_SERVED(70029, "err.trade.pickup_point_not_served"),
    /** 自建取货点归不到任何社区：没定位到、经营范围也空。要他先框一个小区，而不是一句「参数有误」 */
    PICKUP_COMMUNITY_REQUIRED(70030, "err.community.pickup_community_required"),
    /** 这一路被运营锁了：商家改不了开关。置灰的按钮点不到，这条只挡绕过界面的请求 */
    CHANNEL_LOCKED(70031, "err.merchant.channel_locked"),
    /*
     * 微信手机号快速验证没给出号码（通道未开、未认证、或本次换取失败）。
     *
     * **单独一个码，且明确报错、不静默回落到验证码表单**：
     * 用户点了「微信一键获取」却看到验证码表单，会以为自己点错了 ——
     * 而真正发生的是那条通道没通。端上据此说「这次没拿到，用验证码试试」并**自己切换**。
     */
    WX_PHONE_UNAVAILABLE(70027, "err.user.wx_phone_unavailable"),
    /*
     * 还有没走完的订单/售后，不能注销。
     *
     * **单独一个码**：端上要把他送到订单列表去，而不是笼统说一句「操作失败」——
     * 他需要知道是哪几单挡着，以及去哪儿看。
     */
    DEREGISTER_HAS_OPEN_ORDERS(70028, "err.user.deregister_has_open_orders"),
    /*
     * 准入矩阵拒绝了这个 (主体档位 × 履约方式) 组合。与 70013 分开：
     * 那个是「这件商品不支持这种送法」（换一种即可），
     * 这个是「这家店不允许用这种送法」（换商品也没用）——
     * 合成一个码，商家会一直换商品试。
     */
    // 2026-08-17 从 70014 挪来：那个号已被 RECEIVER_REQUIRED 占着，
    // 两者撞号意味着「没选地址」与「这家店不能用这种送法」在端上分不开 ——
    // 前者要把人送去地址簿，后者要让他换一家买。ErrorCodeUniqueTest 守这条
    FULFILLMENT_TIER_DENIED(70024, "err.trade.fulfillment_tier_denied"),
    /*
     * 该商家的收款额度已用尽。单独一个码，因为它对三方的解法都不同：
     * 买家该换一家买、商家该去升主体、运营该去核对额度口径。
     * 不拦的话它会在付款那一刻表现为通道侧的「支付失败」——
     * 那时候平台既解释不清，也补救不了。
     */
    MERCHANT_QUOTA_EXHAUSTED(70019, "err.trade.merchant_quota_exhausted"),

    /**
     * 进项票未核验通过，不允许登记付款（**票到付款**）。
     *
     * <p>不复用 CONFLICT 的理由与 GOODS_NOT_APPROVED 一样：财务看到「操作冲突」
     * 完全不知道该去做什么，而看到「发票未核验」就知道要先去催票或核验。
     */
    INVOICE_REQUIRED(70026, "err.settle.invoice_required"),
    /** 发票金额与应付合计不符。多半是周期选错或漏了几单 */
    INVOICE_AMOUNT_MISMATCH(70016, "err.settle.invoice_amount_mismatch"),
    /** 开票方名称与供应商主体名不一致 —— 三流不一致会被认定虚开风险 */
    INVOICE_TITLE_MISMATCH(70017, "err.settle.invoice_title_mismatch"),

    /**
     * 多规格商品不支持商品级限时特价。
     *
     * <p>此前返回通用的 BAD_REQUEST（「请求参数有误」），商家只会反复改价格与时间，
     * 而问题在于「这件商品有两个规格，而活动价只有一个」。
     * 单独一个码，端上才能把这句话说出来。
     */
    FLASH_MULTI_SKU_UNSUPPORTED(70004, "err.goods.flash_multi_sku"),
    /**
     * 这类活动不支持限定门店。
     *
     * <p>只有满减能限定门店：它在**算价时**生效，那时顾客已经选好自提点，
     * 「货从哪家店出」是确定的。限时特价与买赠改的是**商品页的展示**
     * （活动价、赠品标），而顾客浏览商品时还没选自提点 ——
     * 允许限定门店就会出现「页面 ¥9.90、下单 ¥12.80」。
     *
     * <p>与「多规格特价被拒」同一个处理：宁可当场说清楚，
     * 也不要让商家建一个悄悄不生效、或悄悄按错价卖的活动。
     */
    CAMPAIGN_STORE_UNSUPPORTED(70005, "err.campaign.store_unsupported"),
    /**
     * 这个角色不能做这件事（B 端）。
     *
     * <p><b>与通用的 {@link #FORBIDDEN} 分开</b>，两个理由：
     * <ol>
     *   <li><b>给的话不一样</b>：通用「没有操作权限」会让店员去找店主要权限，
     *       而店主在界面上根本找不到「给店员开结算权限」这个开关 ——
     *       设计上就没有。要说的是「结算只有店主能看」。</li>
     *   <li><b>排查时分得开</b>：B 端还有一类 403 来自作用域
     *       （这家店没有自提点、这单不属于本店）。两者撞同一个码时，
     *       「权限没配对」和「数据不在范围里」在日志里长得一模一样 ——
     *       实测就是靠这个码把它们分开的。</li>
     * </ol>
     */
    BIZ_ROLE_FORBIDDEN(70006, "err.biz.role_forbidden"),

    /**
     * 经营范围不是合法取值，或这一期没开放这一档（{@code sys_setting} 的
     * {@code merchant.service-scope-enabled}）。
     *
     * <p>单独一个码而不是复用 BAD_REQUEST：这两种情况商家都无从自己发现 ——
     * 通用的「请求参数有误」会让他去改地址、改营业时间，而问题在那个下拉框上。
     * 端上据此把「当前只开放本社区与全市」直接说出来。
     */
    SERVICE_SCOPE_NOT_ALLOWED(70018, "err.merchant.service_scope_not_allowed"),

    /**
     * 门店数量已到套餐上限。
     *
     * <p>不复用 {@code BAD_REQUEST}：那句「请求参数有误」把一个<b>额度问题</b>
     * 说成了<b>输入问题</b> —— 商家会回去反复改门店名，而无论怎么改都一样被拒。
     * 他要做的是升套餐（或先停用一家），这两件事之间没有任何关系。
     */
    STORE_QUOTA_EXCEEDED(70020, "err.merchant.store_quota_exceeded"),

    /**
     * 门店被平台强制下线，商家的启停操作一律拒绝。
     *
     * <p>不复用 {@code BAD_REQUEST}：商家看到「请求参数有误」会反复重试启用按钮，
     * 而他该做的是联系平台申诉 —— 解除只能由平台做，这正是强制下线与
     * 自主停用（READONLY）分成两个值的原因。
     */
    STORE_SUSPENDED_BY_PLATFORM(70021, "err.merchant.store_suspended_by_platform"),

    /**
     * 子账号数量已达套餐上限（P-11.2）。
     *
     * <p>与 {@link #STORE_QUOTA_EXCEEDED} 分成两个码而不是共用一个「额度不足」：
     * 两者的解法不同 —— 一个是停用一家旧店或升档，一个是停用一个旧账号或升档，
     * 而商家看到的提示决定他下一步做什么。
     *
     * <p>三个参数是**现在几个、上限几个、当前档位** —— 只说「额度不足」，
     * 他的下一步是打客服电话。
     */
    STAFF_QUOTA_EXCEEDED(70022, "err.merchant.staff_quota_exceeded"),

    /**
     * 当前档位没有这项能力位（一期只有 {@code cross_store_stats}，B-11.12.5/6）。
     *
     * <p><b>明确拒绝，不返回空数据</b>：跨店总览返回一个空列表，商家看到的是
     * 「我明明有两家店，这一页却什么都没有」—— 他会当成故障去找客服，
     * 而这本该是一次升档的机会。空数据把「你还没买这个」说成了「它坏了」。
     *
     * <p>唯一的参数是<b>当前档位</b>：只说「无权访问」，商家不知道自己差在哪、
     * 也不知道升到哪一档才有。与 {@link #BIZ_ROLE_FORBIDDEN}(70006) 分开 ——
     * 那个是「你这个角色不能看」（解法是找老板授权），
     * 这个是「这家店还没买这个包」（解法是升档），两者的下一步动作完全不同。
     */
    PLAN_CAPABILITY_REQUIRED(70023, "err.merchant.plan_capability_required"),

    // ---- 8xxxx 类目维护 ----
    /** 类目最多两级（V168 由三级收敛）—— 端上的选择器只渲染两层，更深的节点查得到、选不到。 */
    CATEGORY_TOO_DEEP(80001, "err.category.too_deep"),
    /** 下面还挂着商品或未归档的子类目 —— 直接归档会让那些商品挂在一个不存在的类目上。 */
    CATEGORY_IN_USE(80002, "err.category.in_use"),
    /** 父类目已归档，恢复它会造出一个挂在已删父节点下的孤儿。 */
    CATEGORY_PARENT_ARCHIVED(80003, "err.category.parent_archived"),
    /**
     * 建品时传了一个查无此项的类目编号。
     *
     * <p>不复用 {@code BAD_REQUEST}，也**不兜底成默认类目**：类目现在是唯一的分类输入，
     * 商品形态由它派生（生鲜要截单、服务不发货）。兜底等于把一条错误数据
     * 静默转成一条合法数据 —— 商家以为自己建的是生鲜，而库里那件货是日用品。
     */
    CATEGORY_NOT_FOUND(80007, "err.category.not_found"),
    /**
     * 删一个底下还有商品的门店经营类目。
     *
     * <p>不拦的话那些商品会挂在一个这家店已经不存在的货架上：店铺页里就此消失，
     * 而商家在商品列表里还看得到它们 —— 两个页面对同一批货给出相反的答案。
     */
    STORE_CATEGORY_IN_USE(80008, "err.store_category.in_use"),

    /**
     * 截单时间不早于到货时间（P-3.3.2）。
     *
     * <p>不复用 {@code BAD_REQUEST}：运营看到「参数有误」会去检查数字格式，
     * 而错的是两个时间的**先后**。截单晚于到货 = 货都到了还在收单，
     * 那批订单没有对应的采购，最后只能挨个退。
     */
    PRESALE_CUTOFF_AFTER_ARRIVAL(80004, "err.presale.cutoff_after_arrival"),

    /**
     * 平台规格模板的选项缺 {@code code}（P-3.4 / B-4.5）。
     *
     * <p>这是平台模板存在的**唯一理由**：自由文本下三家店会把同一件事写成
     * 「5 斤」「五斤」「2.5kg」，聚合、比价、搜索全部对不上。
     * 一个没有 code 的平台模板与商家手输的没有区别，它只让人**以为**规格统一了。
     */
    SPEC_TEMPLATE_CODE_REQUIRED(80005, "err.spec_template.code_required"),

    /**
     * 同一品类下模板重名，或同一模板里两个选项的 code 相同。
     *
     * <p>前者会让商家的下拉里出现两个「重量」，选哪个都对不上；
     * 后者会让「500g」和「1kg」在聚合时并成同一个规格 —— 而那正是 code 要防的事。
     */
    SPEC_TEMPLATE_DUPLICATE(80006, "err.spec_template.duplicate"),

    /*
     * 触达通道的模拟发送（P-14.1 / TDD-运营端触达中心 §5）。
     *
     * <p>**不复用 BAD_REQUEST**：运营看到「请求参数有误」会去改输入框里的 userNo，
     * 而这两种情况下 userNo 是对的 —— 问题在那个用户的状态上，且各自的下一步动作不同：
     * 一个要换测试账号，一个要让那个人先装 App 登录一次。
     */
    /** 该用户没有可用的微信订阅额度，测试会白发（发出去也会被微信以 43101 拒） */
    NOTIFY_WX_QUOTA_EMPTY(80101, "err.notify.wx_quota_empty"),
    /** 该用户没有绑定 App 设备：没装、没登录过 App，或已登出解绑 */
    NOTIFY_NO_DEVICE(80102, "err.notify.no_device");

    private final int code;
    private final String msgKey;

    ErrorCode(int code, String msgKey) {
        this.code = code;
        this.msgKey = msgKey;
    }

    public int code() {
        return code;
    }

    public String msgKey() {
        return msgKey;
    }
}
