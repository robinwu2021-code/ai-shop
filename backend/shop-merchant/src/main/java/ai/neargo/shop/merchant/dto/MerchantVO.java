package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.merchant.entity.MchEntity;

import java.util.List;

/**
 * 商家详情（对齐 c-app {@code Merchant}）。评分在库里是 ×10 的整数，这里除回一位小数。
 */
public record MerchantVO(String merchantNo,
                         String name,
                         String logo,
                         double rating,
                         boolean verified,
                         int breachCount,
                         String type,
                         String desc,
                         int salesCount,
                         int ratingCount,
                         int goodsCount,
                         String address,
                         String openHours,
                         long joinedAt,
                         List<String> tags,
                         Scores scores) {

    public record Scores(double goods, double service, double speed) {
    }

    /**
     * @param address   来自门面表 {@code mch_store}，不是主体表（V42 起主体上没有这两列）
     * @param openHours 同上
     */
    public static MerchantVO of(MchEntity m, List<String> tags, String address, String openHours) {
        return new MerchantVO(
                m.getEntityNo(), m.getName(), m.getLogo(),
                score(m.getRating()), Boolean.TRUE.equals(m.getVerified()), nz(m.getBreachCount()),
                m.getLegalForm(), m.getDescription(), nz(m.getSalesCount()), nz(m.getRatingCount()),
                nz(m.getGoodsCount()), address, openHours,
                m.getJoinedAt() == null ? 0L : m.getJoinedAt(), tags,
                new Scores(score(m.getScoreGoods()), score(m.getScoreService()), score(m.getScoreSpeed())));
    }

    /**
     * 商品卡/评价卡上的商家信息（对齐 c-app {@code MerchantBrief}）。
     *
     * @param selfOperated 是否平台自营。<b>电商法 §37 要求显著标记，是法定义务</b>。
     *                     由 {@code funds_mode} 派生而不是查门店：
     *                     <ul>
     *                       <li>法律上「谁是销售主体」看的是<b>钱进谁的账户</b> ——
     *                           归集即平台收款、平台开票、平台担责，那就是自营</li>
     *                       <li>门店级的 {@code business_mode} 要按店查，
     *                           而这个 Brief 出现在每一张商品卡上，会变成 N+1</li>
     *                     </ul>
     */
    public record Brief(String merchantNo, String name, String logo,
                        double rating, boolean verified, int breachCount,
                        boolean selfOperated) {

        public static Brief of(MchEntity m) {
            return new Brief(m.getEntityNo(), m.getName(), m.getLogo(),
                    score(m.getRating()), Boolean.TRUE.equals(m.getVerified()), nz(m.getBreachCount()),
                    // 空按自营：今天唯一在跑的就是归集，而**漏标自营是法定义务的缺失**，
                    // 多标一个只是显示问题
                    m.getFundsMode() == null
                            || ai.neargo.shop.spi.user.MerchantQueryPort.FUNDS_AGGREGATED
                                    .equals(m.getFundsMode()));
        }
    }

    private static double score(Integer x10) {
        return x10 == null ? 0d : x10 / 10d;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
