package ai.neargo.shop.promotion.dto;

import java.util.List;

/** 社区集单的一期（B 端 s20 / s31 / s33）。份数与金额都是从订单现算的，不是存的。 */
public final class PeriodVOs {

    private PeriodVOs() {
    }

    /**
     * @param qty          已付款且未退的份数
     * @param customers    下单人数（去重）
     * @param amountMinor  已付款且未退的金额（分）
     * @param minQty       起订量，空 = 不设
     * @param periodQuota  每期上限，空 = 不限
     */
    public record PeriodVO(String periodNo, String activityNo, String activityName,
                           String periodDate, long cutoffAt, String pickupDate, String pickupFrom,
                           String status, int qty, int customers, long amountMinor,
                           Integer minQty, Integer periodQuota, Long decideDeadline) {
    }

    public record GoodsQty(String goodsNo, String title, int qty) {
    }

    public record PickupQty(String pickupNo, String pickupName, int qty) {
    }

    public record PeriodDetailVO(PeriodVO period, List<GoodsQty> byGoods, List<PickupQty> byPickup) {
    }

    /** 给进销存进货单预填：按 SKU 汇总 */
    public record PurchaseLineVO(String skuNo, String goodsNo, String title, String spec, int qty) {
    }

    /** s33 的两个选择 */
    public enum Decision { CANCEL, PROCEED }
}
