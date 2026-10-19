package ai.neargo.shop.trade.order.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 权限不足时只下发canCancel/blockedCode，不泄漏主单金额及隐藏兄弟单。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancellationImpact(boolean canCancel, String blockedCode, String requestedOrderNo,
                                 String payOrderNo, Long totalPayableMinor, String currency,
                                 List<AffectedOrder> affectedOrders) {
    public static CancellationImpact blocked(String code) {
        return new CancellationImpact(false, code, null, null, null, null, null);
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AffectedOrder(String subOrderNo, String merchantNo, String merchantName,
                                String storeNo, String storeName, long payableMinor, String status) { }
}
