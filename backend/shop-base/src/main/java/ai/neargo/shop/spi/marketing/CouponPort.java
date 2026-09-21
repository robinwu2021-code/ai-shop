package ai.neargo.shop.spi.marketing;

import java.util.List;

/**
 * trade → marketing：下单时的券计算与核销。
 *
 * <p>**分摊在 marketing 侧算、trade 侧只落库**：分摊规则属于营销域的知识
 * （门槛、出资方、商家券只作用于本店），放在 trade 里算意味着每加一种券型都要改交易主干。
 */
public interface CouponPort {

    /**
     * 计算券在各子单上的分摊。
     *
     * @param groups 按商家分组的商品额（拆单之后、优惠之前）
     * @throws ai.neargo.shop.common.BizException 券不可用（不属于本人/已用/过期/不满门槛）
     */
    Allocation allocate(String userNo, String userCouponNo, List<MerchantAmount> groups);

    /**
     * 下单成功后核销。
     *
     * @param allocation 这一单<b>实际减掉的分摊</b>（{@link #allocate} 的返回值原样带过来）。
     *                   新模型要把它记进 {@code pmt_apply} —— 那一行记的是
     *                   「<b>当时</b>减了多少」，而重算依赖的规则会变：
     *                   同一张券在三个月后重算，可能因为门槛改过、封顶调过而对不上账。
     *                   老实现忽略这个参数（它只在用户券上盖一个 order_no）。
     */
    void markUsed(String userNo, String userCouponNo, String orderNo, Allocation allocation);

    /** 订单取消/关闭时退回券。 */
    void release(String orderNo);

    /**
     * 这个人手里<b>此刻可用</b>的券（用户持有的那张的号）。下单页枚举「活动 × 券」
     * 找最省组合时用（优惠券全链路梳理 批 2）。门槛、店铺这些「这一单能不能用」
     * 不在这里判 —— 交给 {@link #allocate}，两处各判一次迟早对不上。
     */
    default List<String> heldUsable(String userNo) {
        return List.of();
    }

    /**
     * 这一单用过、**现在已回到券包**的券名；没有返回 null（待办设计 P3）。
     * 从券的现状查，不从订单状态推 —— 退款关着退券开关时，券其实没回来。
     */
    default String returnedTitleOf(String orderNo) {
        return null;
    }

    /** 这一单此刻<b>正占用着</b>的券名（整单退款前告诉商家「会退回哪张券」）；没有返回 null */
    default String usedTitleOf(String orderNo) {
        return null;
    }

    record MerchantAmount(String merchantNo, long goodsAmount) {
    }

    /**
     * @param byMerchant 出资方是商家（决定记 {@code discount_merchant} 还是 {@code discount_platform}，Q9）
     */
    record Allocation(long totalDiscount, boolean byMerchant, List<MerchantDiscount> shares,
                      /**
                       * 券名，**给买家看的那个**（TDD-C端优惠依据）。
                       * 算分摊时本来就读了券行，顺手带出来 —— 否则确认页为了一个名字要再查一次。
                       * 取不到就是空：端上退回只显示金额，<b>不编名字</b>。
                       */
                      String title) {

        public Allocation(long totalDiscount, boolean byMerchant, List<MerchantDiscount> shares) {
            this(totalDiscount, byMerchant, shares, null);
        }

        public static Allocation none() {
            return new Allocation(0L, false, List.of(), null);
        }

        public long discountOf(String merchantNo) {
            return shares.stream().filter(s -> s.merchantNo().equals(merchantNo))
                    .mapToLong(MerchantDiscount::amount).sum();
        }
    }

    record MerchantDiscount(String merchantNo, long amount) {
    }
}
