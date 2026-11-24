package ai.neargo.shop.invoice.dto;

import ai.neargo.shop.invoice.dto.InvoiceDtos.BuyerReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public final class InvoiceApplicationDtos {

    private InvoiceApplicationDtos() {
    }

    public record SubmitApplicationReq(
            @NotBlank @Size(max = 32) String sourceType,
            @NotBlank @Size(max = 64) String sourceNo,
            @Valid BuyerReq buyer) {}

    public record ReviseApplicationReq(
            @NotNull @Positive Integer expectedRevision,
            @Valid BuyerReq buyer,
            @NotBlank @Size(max = 256) String changeReason) {}

    public record IssueApplicationReq(@NotNull @Positive Integer expectedRevision) {}

    public record ChangeApplicationStateReq(
            @NotNull @Positive Integer expectedRevision,
            @NotBlank @Size(max = 256) String reason) {}

    public record ApplicationView(String applicationNo, String sourceType, String sourceNo, int currentRevision,
            String status, String issuedInvoiceNo, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record BuyerView(String taxId, String name, String carrierType, String carrierIdMasked, String donateCode) {}

    public record RevisionView(int revisionNo, BuyerView buyer, String currency, long sourceAmountMinor,
            String sourceFingerprint, String submittedBy, LocalDateTime submittedAt) {}

    public record EventView(int revisionNo, String action, String fromStatus, String toStatus, String actorNo,
            String reason, String relatedInvoiceNo, LocalDateTime occurredAt) {}
}
