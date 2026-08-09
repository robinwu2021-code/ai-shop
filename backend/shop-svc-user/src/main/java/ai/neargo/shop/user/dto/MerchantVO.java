package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.merchant.entity.MchEntity;

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

    /** 商品卡/评价卡上的商家信息（对齐 c-app {@code MerchantBrief}）。 */
    public record Brief(String merchantNo, String name, String logo,
                        double rating, boolean verified, int breachCount) {

        public static Brief of(MchEntity m) {
            return new Brief(m.getEntityNo(), m.getName(), m.getLogo(),
                    score(m.getRating()), Boolean.TRUE.equals(m.getVerified()), nz(m.getBreachCount()));
        }
    }

    private static double score(Integer x10) {
        return x10 == null ? 0d : x10 / 10d;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
