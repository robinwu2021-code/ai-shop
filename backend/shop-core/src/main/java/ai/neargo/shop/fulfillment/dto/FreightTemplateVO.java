package ai.neargo.shop.fulfillment.dto;

import java.util.List;

/**
 * 运费模板与超区规则（P-5.2.3）。
 *
 * <p><b>重量一律克、金额一律分，都是整数</b> —— 避免 0.1kg + 0.2kg 这类浮点误差
 * 在算钱的地方冒出来。
 *
 * @param freeThreshold 满多少分免邮；0 = 不免邮
 * @param isDefault     默认模板不可归档 —— 归档之后新商家没有模板可用
 * @param outOfRange    超区规则。必填数组：端上直接 {@code .map} 渲染
 * @param archivedAt    归档时间（ISO-8601），null = 在用。<b>软删除不是删除</b>：
 *                      硬删会把历史订单的运费依据一起抹掉
 */
public record FreightTemplateVO(String templateNo, String name,
                                int firstWeightGram, long firstFee,
                                int addWeightGram, long addFee,
                                long freeThreshold, boolean isDefault,
                                List<OutOfRangeVO> outOfRange,
                                String archivedAt, String updatedAt, String updatedBy) {

    /**
     * @param region    省或直辖市名
     * @param action    REJECT=不配送 / SURCHARGE=加价配送
     * @param surcharge 加价金额（分）。REJECT 时必须为 0 —— 不配送就没有「加多少钱」这回事
     */
    public record OutOfRangeVO(String region, String action, long surcharge) {
    }
}
