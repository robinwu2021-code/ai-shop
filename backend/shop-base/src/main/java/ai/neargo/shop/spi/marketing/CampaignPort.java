package ai.neargo.shop.spi.marketing;

import java.util.List;

/**
 * trade → marketing：下单时的**店铺活动**优惠计算。
 *
 * <p><b>为什么要有这个 Port，而不是复用 {@link CouponPort}</b>：券与活动是两件事。
 * 券是用户**主动选**的（要领、要挑、一单一张），活动是**自动生效**的
 * （满额就减，用户不做任何动作）。两者的可用性判定、出资方、叠加规则都不同，
 * 硬塞进一个接口会让「这张券为什么没减」和「这个满减为什么没生效」共用一条排查路径。
 *
 * <p><b>它补上的是一条断了的链路</b>：`mkt_campaign` 表此前**没有任何消费方** ——
 * 读它的只有它自己的 mapper/service/controller。商家在 B 端建了满减活动，
 * 后端存下来了，下单时一分钱不减，而商家侧界面显示活动「进行中」。
 * 四层测试全绿，因为测的是「活动能不能建」，没有一条测「建了之后金额有没有变」。
 */
public interface CampaignPort {

    /**
     * 按商家算自动优惠（当前只有满减 {@code FULL_CUT}）。
     *
     * <p><b>与券的先后顺序</b>：先算活动，再算券 —— 券作用在活动优惠**之后**的金额上。
     * 理由是用户视角：满减是他没得选的（满额就减），券是他挑的；
     * 「这张券帮我省了多少」应该是在已有优惠基础上的增量，否则同一张券在不同订单里
     * 显示的减免额会对不上他自己的心算。
     *
     * @param groups 按商家分组的商品额（拆单之后、任何优惠之前）
     * @return 每个商家的活动优惠额；没有生效活动时返回空分摊
     */
    Discount autoDiscount(List<MerchantAmount> groups);

    /**
     * 限时特价：这些商品此刻的活动价。
     *
     * <p>返回的 Map 只含**命中活动**的商品，没命中的键不出现 —— 调用方按
     * 「有就覆盖、没有就用原价」处理，不必判断 0 与 null 的差别。
     *
     * <p><b>为什么是 goodsNo 而不是 skuNo</b>：`mkt_campaign` 的模型就是
     * 「这几个商品 + 一个活动价」（{@code goods_nos} + {@code flash_price_minor}），
     * 一个商品的所有 SKU 共用这个价。
     * ⚠️ 多规格商品（10 斤装 / 5 斤装）因此会被拉成同一个价 —— 这是**模型的限制**
     * 而不是实现取舍，要改得先给活动加 SKU 维度。已在方案里记为待确认。
     *
     * @param goodsNos 要查的商品；空集合直接返回空 Map，不打库
     */
    java.util.Map<String, Long> flashPrices(java.util.Collection<String> goodsNos);

    /**
     * 买赠规则：这些商品此刻的「买 N 送 M」。
     *
     * <p>只含命中活动的商品。同一商品命中多个时取**送得最多**的那个 ——
     * 与满减「取最优」、特价「取最低价」同一口径：都往对用户有利的一侧走，
     * 商家不会因为多建一个活动而少送。
     *
     * <p>⚠️ <b>赠品只能是同款</b>：{@code mkt_campaign} 里只有 buyN / giftM，
     * 没有「赠哪件」的字段。端上的 `Promotion` 类型有 giftGoodsNo（买米送油），
     * 后端表达不了 —— 又一处**同一件事两处建模且不一致**，已记入待确认。
     */
    java.util.Map<String, GiftRule> giftRules(java.util.Collection<String> goodsNos);

    /**
     * 买 N 送 M。
     *
     * <p>口径：**付 N 件的钱，收到 N+M 件**（买 2 送 1，买 4 件送 2 件）。
     * 另一种常见口径是「每 N+M 件里有 M 件免费」（买 3 付 2），买 4 件只送 1 件。
     * 取前者是因为它与商家口头说的「买二送一」一致，用户不会算错
     * （与端上 `shared/utils/promotion.ts` 同一口径，两边必须一致，
     * 否则页面显示送 2 件而实际发 1 件）。
     */
    record GiftRule(int buyN, int giftM) {

        public int giftQty(int bought) {
            if (buyN <= 0 || giftM <= 0 || bought < buyN) {
                return 0;
            }
            return bought / buyN * giftM;
        }
    }

    record MerchantAmount(String merchantNo, long goodsAmount) {
    }

    /**
     * 活动优惠的分摊结果。
     *
     * <p>没有 {@code byMerchant} 字段 —— 店铺活动的出资方**恒为商家**
     * （{@code mkt_campaign.entity_no NOT NULL}，活动是店铺级的，平台不出这个钱）。
     * 券那边需要这个字段是因为平台券与商家券并存。
     */
    record Discount(long total, List<MerchantDiscount> shares) {

        public static Discount none() {
            return new Discount(0L, List.of());
        }

        public long of(String merchantNo) {
            return shares.stream().filter(s -> s.merchantNo().equals(merchantNo))
                    .mapToLong(MerchantDiscount::amount).sum();
        }
    }

    record MerchantDiscount(String merchantNo, long amount) {
    }
}
