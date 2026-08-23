package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 运营端「类目 × 规格」总览的一行。
 *
 * <p>为什么连<b>一条规格都没绑的类目也要返回</b>：这张表真正的用途是回答
 * 「哪些类目还没配规格」——只列已配的，缺口就永远看不见，而缺口的代价是
 * 那一类商家建品时只能手打，手打的选项没有 code，聚合就此断掉。
 *
 * @param dimCount 已绑维度数；0 就是缺口
 */
public record CategorySpecVO(String categoryNo, String categoryName, String parentName,
                             String categoryType, int dimCount, List<DimVO> dims) {

    /**
     * @param primary    主维度：建品选完类目自动预填的就是它
     * @param usage      SALE 进 SKU / PROP 只是描述（类目可覆盖维度上的默认值）
     * @param universal  通用维度：值的含义跨类目一致（颜色、重量），与只在本类目成立的专用维度相对
     * @param valueCount 该类目下可选的取值个数（裁剪过子集就是子集的个数）
     */
    public record DimVO(String dimNo, String code, String name, String valueType, String unit,
                        String usage, boolean universal, boolean primary,
                        int valueCount, List<ValueVO> values) {
    }

    /** @param label 该类目下的展示文案，可能被 label_override 换过说法（500g → 约1斤） */
    public record ValueVO(String valueNo, String code, String label,
                          java.math.BigDecimal numericValue, String numericUnit) {
    }
}
