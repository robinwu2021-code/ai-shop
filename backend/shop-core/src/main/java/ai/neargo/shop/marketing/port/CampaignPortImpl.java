package ai.neargo.shop.marketing.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.campaign.CampaignJson;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.spi.marketing.CampaignPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * trade → marketing：店铺活动的自动优惠（{@link CampaignPort}）。
 *
 * <p>当前只实现满减 {@code FULL_CUT}。另外三种各自缺不同的东西：
 * <ul>
 *   <li>{@code FLASH} 限时特价 —— 要改的是**商品价格**，接入点不在下单而在商品查询，
 *       且要处理「加购时是特价、下单时已过期」
 *   <li>{@code BUY_GIFT} 买赠 —— 要往订单里**加赠品行**（价格 0），
 *       还牵扯赠品库存与售后退赠
 *   <li>{@code COUPON} 店铺券 —— 要在保存活动时往 {@code mkt_coupon} 写一张券，
 *       之后走已有的 {@link ai.neargo.shop.spi.marketing.CouponPort} 链路
 * </ul>
 * 满减先做，因为它接入点单一（只在下单算价）、无副作用（不改价、不加行）、可测性最好。
 */
@Component
public class CampaignPortImpl implements CampaignPort {

    private final CampaignMapper campaignMapper;
    private final ObjectMapper json;

    public CampaignPortImpl(CampaignMapper campaignMapper, ObjectMapper json) {
        this.campaignMapper = campaignMapper;
        this.json = json;
    }

    @Override
    public Discount autoDiscount(List<MerchantAmount> groups) {
        if (groups == null || groups.isEmpty()) {
            return Discount.none();
        }
        long now = System.currentTimeMillis();
        List<MerchantDiscount> shares = new ArrayList<>();
        long total = 0L;
        for (MerchantAmount g : groups) {
            long off = fullCutOf(g.merchantNo(), g.storeNo(), g.goodsAmount(), now);
            if (off > 0) {
                shares.add(new MerchantDiscount(g.merchantNo(), off));
                total += off;
            }
        }
        return shares.isEmpty() ? Discount.none() : new Discount(total, shares);
    }

    @Override
    public void commit(String orderNo, Discount discount) {
        // 老模型没有限量的概念（mkt_campaign 只有 total_count，且那是给券用的），
        // 所以这里没有东西可扣。P9 老表退场时这个空实现一起消失
    }

    @Override
    public java.util.Map<String, Long> flashPrices(java.util.Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return java.util.Map.of();
        }
        long now = System.currentTimeMillis();
        List<MktCampaign> running = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectList(Wrappers.<MktCampaign>lambdaQuery()
                        .eq(MktCampaign::getType, MktCampaign.FLASH)
                        .eq(MktCampaign::getStatus, MktCampaign.RUNNING)
                        .le(MktCampaign::getStartAt, now)
                        .ge(MktCampaign::getEndAt, now)));
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (MktCampaign c : running) {
            Long price = c.getFlashPriceMinor();
            if (price == null || price <= 0) {
                continue;
            }
            for (String goodsNo : CampaignJson.readStringList(json, c.getGoodsNos())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                // 同一商品命中多个特价活动时取**最低价**：对用户有利的一侧，
                // 且与满减「取最优」同一口径，商家不会因为多建一个活动而卖得更贵
                out.merge(goodsNo, price, Math::min);
            }
        }
        return out;
    }

    @Override
    public java.util.Map<String, GiftRule> giftRules(java.util.Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return java.util.Map.of();
        }
        long now = System.currentTimeMillis();
        List<MktCampaign> running = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectList(Wrappers.<MktCampaign>lambdaQuery()
                        .eq(MktCampaign::getType, MktCampaign.BUY_GIFT)
                        .eq(MktCampaign::getStatus, MktCampaign.RUNNING)
                        .le(MktCampaign::getStartAt, now)
                        .ge(MktCampaign::getEndAt, now)));
        java.util.Map<String, GiftRule> out = new java.util.HashMap<>();
        for (MktCampaign c : running) {
            Integer buyN = c.getBuyN();
            Integer giftM = c.getGiftM();
            if (buyN == null || giftM == null || buyN <= 0 || giftM <= 0) {
                continue;
            }
            GiftRule rule = new GiftRule(buyN, giftM);
            for (String goodsNo : CampaignJson.readStringList(json, c.getGoodsNos())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                // 命中多个时取「买同样多送得更多」的：按买 1 件能送几件比较
                out.merge(goodsNo, rule, (a, b) ->
                        b.giftQty(b.buyN()) * a.buyN() > a.giftQty(a.buyN()) * b.buyN() ? b : a);
            }
        }
        return out;
    }

    /**
     * 单个商家的满减额。
     *
     * <p><b>同店多个满减活动只取最优的一个，不叠加</b>（含门店级与全主体级同时命中）。 叠加会让商家自己算不清成本 ——
     * 建两个「满100减10」就变成满100减20，而界面上每个活动都只显示自己那一条。
     *
     * <p>下单查的是**下单那一刻**的活动：活动的起止时间与状态都在这里判，
     * 不信任端上传来的任何优惠额。端上算的那份只用于展示。
     */
    private long fullCutOf(String merchantNo, String storeNo, long goodsAmount, long now) {
        List<MktCampaign> running = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectList(Wrappers.<MktCampaign>lambdaQuery()
                        .eq(MktCampaign::getEntityNo, merchantNo)
                        .eq(MktCampaign::getType, MktCampaign.FULL_CUT)
                        .eq(MktCampaign::getStatus, MktCampaign.RUNNING)
                        .le(MktCampaign::getStartAt, now)
                        .ge(MktCampaign::getEndAt, now)));
        long best = 0L;
        for (MktCampaign c : running) {
            /*
             * 门店级活动只对**这单出货的那家店**生效。
             *
             * 没有门店上下文时（storeNo 为空）只认全主体活动 —— 不是「全都认」：
             * 那会让「河坊街店开业满减」减到总店的单上，而商家为一家店做的让利
             * 被全主体吃掉，他要到对账时才发现。
             */
            String only = c.getStoreNo();
            if (only != null && !only.isBlank() && !only.equals(storeNo)) {
                continue;
            }
            long threshold = c.getThresholdMinor() == null ? 0L : c.getThresholdMinor();
            long off = c.getDiscountMinor() == null ? 0L : c.getDiscountMinor();
            if (goodsAmount < threshold || off <= 0) {
                continue;
            }
            // 不能减成负数：优惠额大于商品额时按商品额封顶（与券同一口径）
            best = Math.max(best, Math.min(off, goodsAmount));
        }
        return best;
    }
}
