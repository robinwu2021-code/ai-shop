package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 一键再来一单结果（C-ST-03）。
 *
 * <p>{@code skipped} 是这个结构存在的理由：**悄悄少加是最糟的处理** ——
 * 用户以为买到了，到货才发现少东西。
 */
public record RebuyResultVO(int addedCount, List<Skipped> skipped) {

    public record Skipped(String title, String reason) {
    }
}
