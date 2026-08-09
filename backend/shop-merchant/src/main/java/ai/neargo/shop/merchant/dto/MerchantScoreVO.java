package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.merchant.entity.MchEntity;

/**
 * 商家评分与**依据**（C-MC-04/05）。
 *
 * <p>{@code basis} 不是装饰：一个只显示「4.8 分」的商家页，用户无法判断这分是怎么来的，
 * 差评商家也会觉得平台在针对他。把口径写出来（B4：评价均分 ×0.8 + 订单量对数 ×0.2）
 * 是「事后信用替代事前审核」这条路线（ADR-003）能站住的前提。
 */
public record MerchantScoreVO(String merchantNo,
                              double rating,
                              int ratingCount,
                              MerchantVO.Scores scores,
                              String basis) {

    private static final String BASIS = "综合评分 = 评价均分 ×0.8 + 订单量对数 ×0.2；新商家有 30 天保护期";

    public static MerchantScoreVO of(MchEntity m) {
        return new MerchantScoreVO(m.getEntityNo(), score(m.getRating()), nz(m.getRatingCount()),
                new MerchantVO.Scores(score(m.getScoreGoods()), score(m.getScoreService()),
                        score(m.getScoreSpeed())),
                BASIS);
    }

    private static double score(Integer x10) {
        return x10 == null ? 0d : x10 / 10d;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
