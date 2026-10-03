package ai.neargo.shop.trade.service;

/**
 * 售后规则：极速退的门槛 + 各环节的时效（TDD-C 端商品详情页·内容丰富度 §3）。
 *
 * <p><b>为什么两件事在一个对象里</b>：它们是同一句对外承诺的两半。详情页上写「极速退款」，
 * 兑现它靠的是「小额即时退」加「商家 48 小时不处理就自动同意」—— 只有前者，
 * 超出阈值的单会无声无息地挂在商家那里，而页面已经承诺过了。
 *
 * <p><b>为什么复用 {@code aftersale.fast-refund-rule} 这个键</b>：运营端那一屏
 * （读 / 写 / 留痕 / 权限 / 测试）早就齐了，而<b>全仓没有一行业务代码读它</b> ——
 * {@code AfterSaleServiceImpl} 当时读的是自己的 {@code @Value} 默认值。于是那一屏显示
 * 「关闭 · 上限 ¥20 · 24 小时内」，线上真正在跑的是「无条件 · ¥100 · 不限时」。
 * 方案里原本打算新开一段 {@code platform.after-sale-sla}，那会变成第四份真源；
 * 接上这一份才是修。
 */
public interface AfterSaleRuleService {

    // ── 时效默认值（淘宝/京东的通行档位，运营可调）────────────────────────────
    /** 商家响应时限：超过就系统自动同意 */
    int DEFAULT_REPLY_HOURS = 48;
    /** 同意退货后买家寄回的时限：超过关闭本次申请 */
    int DEFAULT_SHIP_BACK_DAYS = 7;
    /** 商家收到退货后确认的时限：超过系统自动退款 */
    int DEFAULT_CONFIRM_HOURS = 48;
    /** 平台介入的承诺时限。**只用于展示与超期告警**，不会触发自动裁决 */
    int DEFAULT_INTERVENE_WORK_DAYS = 5;

    /**
     * 极速退金额上限（分）。
     *
     * <p>取后端原先 {@code @Value} 的那个数（¥100），不是端上那份 ¥50 ——
     * 端上那份小一半，改成它等于把已经在生效的极速退范围砍掉一半。
     */
    long DEFAULT_MAX_AMOUNT = 10_000L;
    /** 下单后多少小时内可极速退 */
    int DEFAULT_WITHIN_HOURS = 72;

    /** 时效的合理区间。越界时按默认值处理并告警 —— 与关单策略同一取舍 */
    int MIN_HOURS = 1;
    int MAX_HOURS = 24 * 30;
    int MIN_DAYS = 1;
    int MAX_DAYS = 90;

    AfterSaleRuleVO get();

    /** 校验后整份覆盖写并留痕。返回写入后的完整值 */
    AfterSaleRuleVO save(AfterSaleRuleVO req, String operatorNo);

    /**
     * 这一笔能不能极速退（仅退款、开关开着、金额在上限内、还在下单后的时限内）。
     *
     * <p>C 端要在**提交之前**就告诉用户会不会秒退，所以这条判定必须能被两侧共用 ——
     * 端上自己拿常量算是此前的做法，那份常量与后端的阈值差了一倍。
     *
     * @param placedAt 下单时间（毫秒），null 视为不受 {@code withinHours} 约束
     */
    boolean instantEligible(String type, long refundMinor, Long placedAt);

    /**
     * @param enabled           极速退总开关。关掉后所有小额售后走人工
     * @param maxAmount         极速退金额上限（分）
     * @param withinHours       下单后多少小时内可极速退
     * @param categories        适用品类编码，空 = 全品类。<b>目前只存不判</b>（见实现注释）
     * @param replyHours        商家响应时限，超时系统自动同意
     * @param shipBackDays      买家寄回时限，超时关闭申请
     * @param confirmHours      商家确认收货时限，超时系统自动退款
     * @param interveneWorkDays 平台介入承诺时限（工作日），仅展示与告警
     */
    record AfterSaleRuleVO(boolean enabled, long maxAmount, int withinHours,
                           java.util.List<String> categories,
                           int replyHours, int shipBackDays, int confirmHours,
                           int interveneWorkDays,
                           String updatedAt, String updatedBy) {
    }
}
