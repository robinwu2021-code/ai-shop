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
 * @param stale   草稿基版过期（线上被别人改过）。true 时端上先引导看差异再重提
 */
public record PublishPreviewVO(List<DiffRow> changes, List<String> blocked, boolean stale) {

    /** @param field 机器名（title/spec/sku0…）；@param label 给人看的中文名 */
    public record DiffRow(String field, String label, String before, String after) {
    }
}
