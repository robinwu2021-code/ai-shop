package ai.neargo.shop.elec.dto;

import java.util.List;

/**
 * 买家面的料号。<b>这里没有、也不许加任何供应商字段</b>：买家看到的库存只有档位，
 * 价格是平台换算后的参考起价。
 */
public final class PartDtos {

    private PartDtos() {
    }

    /**
     * @param mfrName     厂牌中文名（没有就英文名）；厂牌未确认时是供应商写的原文，可能为空
     * @param mfrKnown    厂牌是否已确认。false 时端上写「厂牌未确认」
     * @param packageName 封装，如 LQFP-48
     * @param market      库存行情；null = 平台库里现在没货（照样可以询价）
     */
    public record PartHit(String partNo, String mpn, String mfrName, boolean mfrKnown, String packageName,
                          String description, Market market, String match) {
    }

    /**
     * 一次搜索的结果。
     *
     * @param keyword    实际拿去搜的料号（规范化之后）；端上用它高亮命中的那一截
     * @param mfrFilter  从输入里认出的厂牌（如输入「TI TPS5433」→ 德州仪器）；没认出为 null
     * @param nearFrom   结果是<b>近似</b>的：原词一条都没命中，退到这个更短的前缀才有结果。
     *                   null = 结果就是原词命中的。端上据此写「没有找到 X，以下是相近的料号」
     * @param hits       最多 30 条。每条的 match：EXACT 完全一致 / PREFIX 开头一致 / CONTAINS 中段一致 / NEAR 近似
     */
    public record SearchResult(String keyword, String mfrFilter, String nearFrom, List<PartHit> hits) {
    }

    /**
     * 批量查（BOM 粘贴）里的一行。
     *
     * @param input   原样一行
     * @param mpn     认出的料号原文；null = 这一行没有像料号的词
     * @param qty     同一行里写的数量；没写为 null
     * @param match   EXACT 库里正好有 / AMBIGUOUS 同一料号多家厂牌、没写厂牌 / PREFIX 只有开头一致的 / NONE 库里没有
     * @param hit     最像的那一条（有货的优先）；NONE 时为 null
     * @param others  除 hit 以外还有几条候选
     */
    public record LookupLine(String input, String mpn, Long qty, String match, PartHit hit, int others) {
    }

    /**
     * @param qtyBand     B1 / B100 / B1K / B10K / B100K / B1M
     * @param sourceBand  ONE / FEW / MANY
     * @param priceFromE6 含税参考起价，百万分之一元；null = 有货但都没报价
     * @param dcYearMax   最新批次年份
     */
    public record Market(String qtyBand, String sourceBand, Long priceFromE6, Integer dcYearMax) {
    }
}
