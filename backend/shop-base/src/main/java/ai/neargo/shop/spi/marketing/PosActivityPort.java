package ai.neargo.shop.spi.marketing;

import java.util.List;

/** Records a locked merchant offer and returns its verified allocation weights in cart order. */
public interface PosActivityPort {
    record Line(String goodsNo, String categoryNo, int quantity, long unitPriceMinor) {}
    record Use(String applyNo, String versionNo, long[] weights, boolean orderOffer) {}

    /** Locks selected heads in stable order before POS evaluates item benefits followed by one order benefit. */
    java.util.Set<String> itemOffers(String tenant, List<String> activityNos);
    Use apply(String tenant, String entity, String store, String orderNo, String subOrderNo,
            String userNo, String currency, String activityNo, long expectedMinor, List<Line> lines);
    void cancel(String orderNo);
}
