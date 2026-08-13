package ai.neargo.shop.merchant.dto;

import ai.neargo.shop.merchant.entity.MchEntity;

/**
 * 商家评分与**依据**（C-MC-04/05）。
 *
 * <p>{@code basis} 不是装饰：一个只显示「4.8 分」的商家页，用户无法判断这分是怎么来的，
 * 差评商家也会觉得平台在针对他。把口径写出来是「事后信用替代事前审核」
 * 这条路线（ADR-003）能站住的前提。
 *
 * <p><b>但它必须与真的算法一致。</b> 这句话原先写的是
 * 「评价均分 ×0.8 + 订单量对数 ×0.2」—— 那是《待完成功能清单》B4 里<b>还没拍板</b>
 * 的方案，而当时的实现连评分都不写回。对着一个没实现的公式解释一个没更新的数字，
 * 商家照着它算不出自己的分，只会更确信平台在针对他。
 */
public record MerchantScoreVO(String merchantNo,
                              double rating,
                              int ratingCount,
                              MerchantVO.Scores scores,
                              String basis) {

    /** 与 {@code ReviewServiceImpl.aggregate()} 一致；B4 的权重定了要两处一起改 */
    private static final String BASIS = "综合评分 = 已通过审核的评价均分；被平台驳回或申诉成立的评价不计入";

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
