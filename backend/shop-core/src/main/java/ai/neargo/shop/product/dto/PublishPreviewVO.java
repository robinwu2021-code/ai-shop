package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 发布预览（双版本，工单步骤 4）：草稿经 dry-run 烘焙后与线上的字段级差异。
 *
 * <p><b>diff 在服务端算</b>：烘焙规则只有服务端有 —— 端上比对原始 payload
 * 会漏掉「文案将随规格库刷新」这类变化，而那正是商家最需要在发布前看见的。
 *
 * @param changes 逐字段差异（before → after）。空 = 草稿与线上一致
 * @param blocked 发布会被拦的档位清单（已停用/合并）—— 让商家在点发布**之前**就看到
 * @param stale   草稿基版过期（线上被别人改过，也包括商家自己的 toggle/改截单）。
 *                true 时发布要带 {@code confirmVersion = baseVersion} 才放行
 * @param baseVersion 此刻线上的 version。**冲突的出路靠它**：这份差异就是以这一版
 *                线上为基准算的，商家看完确认，端上把它原样带回 publish ——
 *                对得上才放行，确认之后线上又变了照样拒
 */
public record PublishPreviewVO(List<DiffRow> changes, List<String> blocked, boolean stale,
                               Long baseVersion) {

    /** @param field 机器名（title/spec/sku0…）；@param label 给人看的中文名 */
    public record DiffRow(String field, String label, String before, String after) {
    }
}
