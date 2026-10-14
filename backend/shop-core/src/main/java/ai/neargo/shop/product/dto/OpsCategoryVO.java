package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 平台端的类目视图（对齐 ops-web {@code lib/types/product.ts} 的 {@code Category}）。
 *
 * <p>与 C 端的 {@link CategoryVO} 是**平铺 vs 树**的区别，不是同一个 DTO 加几个字段：
 * 运营要的是一张能搜索、能看到 skuCount、能看到资质门槛的表；
 * C 端要的是一棵能直接渲染的树。硬合成一个，两边都要在响应里挑自己不用的字段。
 */
public record OpsCategoryVO(String categoryNo,
                            String name,
                            String parentNo,
                            int level,
                            /** STANDARD / FRESH / SERVICE / VIRTUAL / VOUCHER */
                            String template,
                            /** 人读的资质名称，展示用。**不是校验依据** */
                            List<String> qualifications,
                            /** 校验依据：经营该类目所需的经营类目编码。空 = 无门槛 */
                            String requiredCode,
                            I18nText i18n,
                            /** 该类目下的商品数 —— 归档校验要用，运营也要据此判断能不能停用 */
                            /**
                             * 同级内的展示顺序，小的在前。
                             *
                             * <p><b>不下发就等于运营改不了顺序</b> —— 而顺序直接决定 C 端
                             * 类目栏里谁排第一，那是这一页最该由人决定的东西之一。
                             */
                            int sort,
                            int skuCount,
                            /** 归档时间。**软删除标记**，有值即视为已归档 */
                            String archivedAt) {

    /** 三语文案。{@code zh} 是基准，{@code en} 缺失时端上按 R9 回落展示 zh。 */
    public record I18nText(String zh, String en) {
    }
}
