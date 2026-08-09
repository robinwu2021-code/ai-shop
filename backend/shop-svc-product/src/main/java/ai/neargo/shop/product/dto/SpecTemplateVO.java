package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 规格模板（对齐 b-app {@code SpecTemplate}）。
 *
 * @param scope PLATFORM（平台统一维护）/ MERCHANT（商家自存）。
 *              商家只能改自己的 —— 平台模板是跨店可比的基础，
 *              一家店改了名字，别家的同名规格就对不上了
 */
public record SpecTemplateVO(String templateNo, String scope, String categoryType,
                             String name, List<Option> options, String merchantNo) {

    /** @param code 来自平台模板的有值，手输的没有。一期只存不用，二期做规格聚合要靠它 */
    public record Option(String code, String label) {
    }
}
