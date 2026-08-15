package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 平台端的规格模板视图（P-3.4 / E27，对齐 ops-web {@code SpecTemplate}）。
 *
 * <p>与商家侧的 {@link SpecTemplateVO} 分成两个类型而不是加字段：那份是**下发给 b-app 的**
 * （商家只需要「有哪些模板、每个模板有哪些选项」），这份多了 {@code status} 与
 * {@code usedByGoods} —— 前者是运营的处置位，后者是「还能不能停用它」的判据。
 * 把这两样塞进商家侧那份，等于把平台的内部状态发到每个商家的建品页上。
 *
 * @param scope       恒为 {@code PLATFORM}。平台端只维护平台模板 ——
 *                    商家自存的模板归商家，运营改了会让那家店的历史规格对不上
 * @param options     {@code [{code,label}]}。<b>平台模板的每个选项都必须有 code</b>：
 *                    自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，
 *                    带 code 才聚合得起来（B-4.5）
 * @param archivedAt  归档时间。**软删除标记**，有值即视为已归档，商家侧不再下发
 */
public record OpsSpecTemplateVO(String templateNo,
                                String scope,
                                /** 按五品类预置：STANDARD / FRESH / SERVICE / VIRTUAL / VOUCHER。空 = 不限类目 */
                                String categoryType,
                                String name,
                                List<Option> options,
                                String archivedAt,
                                String createdAt) {

    /** @param code 聚合键。**平台模板必填** —— 没有它这份模板与商家手输的没有任何区别 */
    public record Option(String code, String label) {
    }
}
