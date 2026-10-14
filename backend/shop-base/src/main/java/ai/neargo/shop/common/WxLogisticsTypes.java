package ai.neargo.shop.common;

/**
 * 我们的六种履约方式 → 微信发货信息录入的四类 {@code logistics_type}。
 *
 * <h2>为什么单独一个类，而且只此一处</h2>
 * 微信只有四类而我们有六种，**不是一一对应**。散成两处写的话，
 * 映射错的表现是 {@code 10060005 invalid logistics_type}，
 * 或者更糟：<b>报上去了但语义是错的</b> —— 那种错微信不会拒，
 * 而它会让「48 小时未发货」的统计与真实履约对不上。
 *
 * <h2>两条反直觉的（TDD §3）</h2>
 * <ul>
 *   <li><b>自提是 4「用户自提」，语义是「商家已备货、用户可来取」</b> ——
 *       不是「等他来取了才算发货」。等到核销才报，48 小时的表会先响；</li>
 *   <li><b>到店核销与预约上门算 3「虚拟商品」，且支付成功那一刻就该报</b> ——
 *       它们在我们这儿根本没有「发货」这个动作（付款即出码），
 *       <b>没有动作 ≠ 不用上报</b>，不报一样结不出钱。
 *       这是最容易整块漏掉的一类。</li>
 * </ul>
 */
public final class WxLogisticsTypes {

    private WxLogisticsTypes() {
    }

    /** 快递。<b>唯一需要运单号 + 快递公司的一类</b>，两者必须成对 */
    public static final int EXPRESS = 1;
    /** 同城配送 */
    public static final int LOCAL_DELIVERY = 2;
    /** 虚拟商品。服务类落这里 */
    public static final int VIRTUAL = 3;
    /** 用户自提 */
    public static final int SELF_PICKUP = 4;

    /**
     * @param fulfillment {@link Fulfillments} 里的六种之一
     * @return 微信的四类之一；<b>认不出来返回 0</b> —— 调用方必须据此不上报并告警，
     *         <b>不要兜一个默认值</b>：兜 1（快递）会让所有自提单缺运单号而被拒，
     *         兜 3（虚拟）则是报上去但语义错，而后者微信不会拒
     */
    public static int of(String fulfillment) {
        if (fulfillment == null) {
            return 0;
        }
        return switch (fulfillment) {
            case Fulfillments.EXPRESS -> EXPRESS;
            case Fulfillments.MERCHANT_DELIVERY -> LOCAL_DELIVERY;
            case Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP -> SELF_PICKUP;
            case Fulfillments.STORE_VERIFY, Fulfillments.APPOINTMENT -> VIRTUAL;
            default -> 0;
        };
    }

    /**
     * 这种履约方式<b>在支付成功那一刻就要上报</b>吗。
     *
     * <p>服务类没有「发货」这个动作，所以没有任何按钮能触发上报 ——
     * 把上报挂在按钮上的写法会整块漏掉它们。
     */
    public static boolean uploadOnPaid(String fulfillment) {
        return Fulfillments.STORE_VERIFY.equals(fulfillment)
                || Fulfillments.APPOINTMENT.equals(fulfillment);
    }

    /** 需要运单号与快递公司吗。**只有快递一类**，且两者必须成对（微信 268485226/227） */
    public static boolean needsTracking(int logisticsType) {
        return logisticsType == EXPRESS;
    }
}
