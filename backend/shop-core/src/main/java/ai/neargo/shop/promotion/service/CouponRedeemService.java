package ai.neargo.shop.promotion.service;

/**
 * 到店核销（P6）：买家出示码、店员扫或输入、当场核销。
 *
 * <p><b>两条硬规则</b>，都写在 {@code TDD-券与活动模型} §4 里：
 * <ol>
 *   <li>{@code STORE_CODE} 券<b>不参与下单算价</b> —— 一张券两条路一定会被用两次，
 *       而对账时谁也说不清是重复核销还是重复抵扣。这条堵在
 *       {@code CouponAllocServiceImpl.assertUsable} 里。</li>
 *   <li><b>线下核销不可撤销</b>：东西已经给出去了。要退只能商家手工补发一张，留痕。
 *       所以 {@code pmt_apply} 上那一行的 {@code reverted_at} 恒为空。</li>
 * </ol>
 */
public interface CouponRedeemService {

    /**
     * 按核销码查这张券能不能核销（<b>先看后核</b>）。
     *
     * <p>店员要先看到「这是谁的、什么券、还剩几次」再按 —— 扫完直接扣的话，
     * 扫错一张就没有回头路（见上面第 2 条）。
     */
    RedeemView peek(String entityNo, String code);

    /**
     * 核销一次。
     *
     * @param storeNo    在哪家门店核销的。**必须记** —— 多店主体对账时要知道货从哪儿出
     * @param operatorNo 哪个店员。不可逆动作必须记名
     */
    RedeemResult redeem(String entityNo, String code, String storeNo, String operatorNo);

    /**
     * @param remaining 还能核销几次（次卡）。一次性券是 1 或 0
     * @param reason    不能核销时的原因码，能核销时为空
     */
    record RedeemView(String userCouponNo, String couponNo, String title, String benefitText,
                      String phoneTail, long expireAt, int timesTotal, int timesUsed,
                      int remaining, boolean redeemable, String reason) {
    }

    /**
     * @param duplicated 命中 3 秒幂等窗口 —— <b>店员连点了两下</b>，不是第二次核销。
     *                   返回上一次的结果而不是报错：报错会让他以为没成功，再点一次
     */
    record RedeemResult(String userCouponNo, int timesUsed, int remaining, boolean usedUp,
                        boolean duplicated) {
    }
}
