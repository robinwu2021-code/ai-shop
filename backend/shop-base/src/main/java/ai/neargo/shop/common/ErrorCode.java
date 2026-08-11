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
    FORBIDDEN(10403, "err.forbidden"),
    NOT_FOUND(10404, "err.not_found"),
    CONFLICT(10409, "err.conflict"),
    TOO_MANY_REQUESTS(10429, "err.too_many_requests"),
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
    /** 给全量角色配数据域：存下来会让人以为限制生效了，而实际没有 */
    STAFF_SCOPE_ON_FULL_ACCESS(10422, "err.staff.scope_on_full_access"),
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

    // ---- 4xxxx 营销 ----
    COUPON_SOLD_OUT(40001, "err.marketing.coupon_sold_out"),
    COUPON_NOT_APPLICABLE(40002, "err.marketing.coupon_not_applicable"),

    // ---- 5xxxx 资金 ----
    SPLIT_RECEIVER_NOT_READY(50001, "err.settle.receiver_not_ready"),
    SPLIT_EXPIRED(50002, "err.settle.split_expired"),

    // ---- 6xxxx 风控 ----
    RISK_BLOCKED(60001, "err.risk.blocked"),

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
     * 准入矩阵拒绝了这个 (主体档位 × 履约方式) 组合。与 70013 分开：
     * 那个是「这件商品不支持这种送法」（换一种即可），
     * 这个是「这家店不允许用这种送法」（换商品也没用）——
     * 合成一个码，商家会一直换商品试。
     */
    FULFILLMENT_TIER_DENIED(70014, "err.trade.fulfillment_tier_denied"),
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
    INVOICE_REQUIRED(70015, "err.settle.invoice_required"),
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

    // ---- 8xxxx 类目维护 ----
    /** 类目最多三级 —— 再深一层 C 端的类目导航就没法展示，也没有第四层的产品定义。 */
    CATEGORY_TOO_DEEP(80001, "err.category.too_deep"),
    /** 下面还挂着商品或未归档的子类目 —— 直接归档会让那些商品挂在一个不存在的类目上。 */
    CATEGORY_IN_USE(80002, "err.category.in_use"),
    /** 父类目已归档，恢复它会造出一个挂在已删父节点下的孤儿。 */
    CATEGORY_PARENT_ARCHIVED(80003, "err.category.parent_archived");

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
