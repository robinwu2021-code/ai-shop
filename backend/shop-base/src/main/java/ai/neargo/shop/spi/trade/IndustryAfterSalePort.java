package ai.neargo.shop.spi.trade;

import java.util.List;

/** Source-bound consumer after-sales through original core policy, approval and refund execution. */
public interface IndustryAfterSalePort {
    record Identity(String tenantNo, String entityNo, String storeNo, String buyerNo, String actorNo) { }
    record Binding(PricingSourceRef source, String orderNo, String subOrderNo) { }
    record LineQty(String sourceLineNo, String componentRef, int qty) { }
    /** No caller amount, allocation, original item id or refund-channel choice is accepted. */
    record Apply(Identity identity, Binding binding, String requestKey, String type, String reason, String reasonCode,
                 List<String> images, List<LineQty> lines) {
        public Apply {
            images = images == null ? List.of() : List.copyOf(images);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
    record QueryApply(Identity identity, Binding binding, String requestKey) { }
    record Lookup(boolean found, Result result) { }
    record Reference(Identity identity, Binding binding, String afterSaleNo) { }
    record Progress(String executionNo, String status, String phase, String errorCode,
                    String resourceStatus, String resourceErrorCode, String closureStatus,
                    String cashStatus, String benefitsStatus, Long succeededAt) { }
    /** Original status and resource closure facts; acceptance/unknown never become synthetic completion. */
    record Result(String afterSaleNo, String orderNo, String subOrderNo, String status,
                  long refundMinor, String currency, Progress progress, String returnInventoryStatus) { }

    Result apply(Apply request);
    Lookup queryApply(QueryApply query);
    Result get(Reference reference);
    Result cancel(Reference reference);
}
