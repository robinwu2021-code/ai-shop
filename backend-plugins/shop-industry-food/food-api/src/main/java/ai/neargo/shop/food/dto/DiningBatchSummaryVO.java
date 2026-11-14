package ai.neargo.shop.food.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Local source summary only: original quote/binding references do not assert current due or payment status. */
public record DiningBatchSummaryVO(String batchNo,String storeNo,String originalCheckNo,List<String> currentCheckNos,
        boolean membershipAuthoritative,String status,String orderSource,LocalDate businessDate,String fulfillment,
        String payMode,String quoteNo,String quoteHash,Instant createdAt,Instant acceptedAt,Instant scheduledAt,
        long version,List<OriginalBindingVO> originalBindings) {
    public DiningBatchSummaryVO {
        currentCheckNos=List.copyOf(currentCheckNos); originalBindings=List.copyOf(originalBindings);
    }
    /** Binding status is the local BOUND/REVERSED association state, never a funds result. */
    public record OriginalBindingVO(String bindingNo,String paymentRootNo,String storeOrderNo,String bindingStatus) { }
}
