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
 *
 * <p><b>{@code platformBorne}：哪些维度不由这个商家负责。</b>
 * 归集路径下平台是销售主体 —— 客服、配送、售后都是平台在做，
 * 而消费者打的「服务」与「时效」分同样会落到这家店头上。
 * 拿它去考核供应商是<b>拿他控制不了的事罚他</b>。
 *
 * <p>但<b>分照常展示给消费者</b>：他打的是这次购物的真实体验，那个信息是真的。
 * 藏起来的直接后果是<b>没人为它负责</b> —— 供应商不背，平台也看不见。
 * 所以这里的做法是**标注归属，不是过滤数据**。
 */
public record MerchantScoreVO(String merchantNo,
                              double rating,
                              int ratingCount,
                              MerchantVO.Scores scores,
                              String basis,
                              java.util.List<String> platformBorne) {

    /** 与 {@code ReviewServiceImpl.aggregate()} 一致；B4 的权重定了要两处一起改 */
    private static final String BASIS = "综合评分 = 已通过审核的评价均分；被平台驳回或申诉成立的评价不计入";

    /**
     * 归集路径下由平台承担的维度。
     *
     * <p>{@code goods}（商品）<b>不在此列</b> —— 货是供应商的，
     * 品质问题该记在他头上，这正是考核要留下的那部分。
     */
    private static final java.util.List<String> PLATFORM_BORNE =
            java.util.List.of("service", "speed");

    /**
     * @param platformIsSeller 平台是否为销售主体（{@code funds_mode == AGGREGATED}）。
     *                         判据跟着钱走，与积分能力、售后分流同一根轴
     */
    public static MerchantScoreVO of(MchEntity m, boolean platformIsSeller) {
        return new MerchantScoreVO(m.getEntityNo(), score(m.getRating()), nz(m.getRatingCount()),
                new MerchantVO.Scores(score(m.getScoreGoods()), score(m.getScoreService()),
                        score(m.getScoreSpeed())),
                BASIS,
                platformIsSeller ? PLATFORM_BORNE : java.util.List.of());
    }

    private static double score(Integer x10) {
        return x10 == null ? 0d : x10 / 10d;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
