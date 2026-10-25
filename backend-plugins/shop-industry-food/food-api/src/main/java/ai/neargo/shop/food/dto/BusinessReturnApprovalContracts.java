package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Receipt;

public final class BusinessReturnApprovalContracts {
    private BusinessReturnApprovalContracts() {}

    public record ApproveReq(Long version, String reason, String replacementCartNo) {}

    public record ReplacementReq(Long version, String replacementBatchNo, String reason) {}

    public record ApprovalVO(
            String approvalNo,
            String returnNo,
            long version,
            String status,
            boolean pending,
            boolean originalPayableChanged,
            Receipt originalAmendment,
            String replacementBatchNo,
            String replacementOrderNo) {}
}
