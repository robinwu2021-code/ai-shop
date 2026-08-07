package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.entity.UsrMerchant;

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

    public static MerchantVO of(UsrMerchant m, List<String> tags) {
        return new MerchantVO(
                m.getMerchantNo(), m.getName(), m.getLogo(),
                score(m.getRating()), Boolean.TRUE.equals(m.getVerified()), nz(m.getBreachCount()),
                m.getType(), m.getDescription(), nz(m.getSalesCount()), nz(m.getRatingCount()),
                nz(m.getGoodsCount()), m.getAddress(), m.getOpenHours(),
                m.getJoinedAt() == null ? 0L : m.getJoinedAt(), tags,
                new Scores(score(m.getScoreGoods()), score(m.getScoreService()), score(m.getScoreSpeed())));
    }

    /** 商品卡/评价卡上的商家信息（对齐 c-app {@code MerchantBrief}）。 */
    public record Brief(String merchantNo, String name, String logo,
                        double rating, boolean verified, int breachCount) {

        public static Brief of(UsrMerchant m) {
            return new Brief(m.getMerchantNo(), m.getName(), m.getLogo(),
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
