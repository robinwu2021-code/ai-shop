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

    record MerchantAmount(String merchantNo, long goodsAmount) {
    }

    /**
     * @param byMerchant 出资方是商家（决定记 {@code discount_merchant} 还是 {@code discount_platform}，Q9）
     */
    record Allocation(long totalDiscount, boolean byMerchant, List<MerchantDiscount> shares) {

        public static Allocation none() {
            return new Allocation(0L, false, List.of());
        }

        public long discountOf(String merchantNo) {
            return shares.stream().filter(s -> s.merchantNo().equals(merchantNo))
                    .mapToLong(MerchantDiscount::amount).sum();
        }
    }

    record MerchantDiscount(String merchantNo, long amount) {
    }
}
