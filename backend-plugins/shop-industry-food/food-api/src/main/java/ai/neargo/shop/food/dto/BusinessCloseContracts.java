package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Source metrics distinguish restaurant metadata from authoritative financial evidence. */
public final class BusinessCloseContracts {
    private BusinessCloseContracts() {}

    public record DayVO(
            String dayNo,
            LocalDate businessDate,
            String policyNo,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            String status,
            String currentCloseRefs,
            long version) {}

    public record Issue(String kind, String sourceRef) {}

    public record ReportVO(
            LocalDate businessDate,
            String currency,
            long batchCount,
            Map<String, BigDecimal> quantitiesByUnit,
            Map<String, BigDecimal> originalOrderServiceQuantitiesByActionUnit,
            String returnAndServiceScope,
            long settlementAttemptCount,
            long requestedDueMinor,
            long returnCount,
            String salesCompleteness,
            String settlementCompleteness,
            String refundCompleteness,
            List<Issue> issues,
            String sourceHash,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<OrderReceivable> originalOrderReceivables,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long effectiveReceivableMinor,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long receivableAdjustmentMinor) {
        public ReportVO(
                LocalDate day,
                String currency,
                long batches,
                Map<String, BigDecimal> quantities,
                Map<String, BigDecimal> service,
                String scope,
                long attempts,
                long requested,
                long returns,
                String sales,
                String settlement,
                String refund,
                List<Issue> issues,
                String hash) {
            this(
                    day,
                    currency,
                    batches,
                    quantities,
                    service,
                    scope,
                    attempts,
                    requested,
                    returns,
                    sales,
                    settlement,
                    refund,
                    issues,
                    hash,
                    null,
                    null,
                    null);
        }

        public ReportVO {
            if (originalOrderReceivables != null)
                originalOrderReceivables = List.copyOf(originalOrderReceivables);
        }
    }

    public record OrderReceivable(
            String batchNo,
            String orderNo,
            String subOrderNo,
            String currency,
            long originalMinor,
            long effectiveMinor,
            long adjustmentMinor,
            String amendmentSourceHash,
            String evidenceHash) {}

    public record CloseVO(
            String closeNo,
            String dayNo,
            String currency,
            int revision,
            String status,
            String snapshotHash,
            String previousCloseNo,
            String signedBy,
            LocalDateTime signedAt,
            ReportVO report,
            long version) {}

    public record PreviewReq(
            @NotNull LocalDate businessDate,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record SignReq(
            @NotBlank @Size(max = 32) String closeNo,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotHash) {}

    /** Local work ownership and two-person review; never a financial settlement approval. */
    public record ExceptionVO(
            String exceptionNo,
            String closeNo,
            String kind,
            String sourceRef,
            String status,
            String ownerNo,
            String nextDayNo,
            String reason,
            String proposedBy,
            String approvedBy,
            LocalDateTime approvedAt,
            String approvalRef,
            long version) {}

    public record ProposeCarryReq(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 32) String ownerNo,
            @NotBlank @Size(max = 32) String nextDayNo,
            @NotBlank @Size(max = 255) String reason) {}

    public record ApproveCarryReq(@NotNull @Min(0) Long version) {}

    public record AdjustmentReq(
            @NotBlank @Size(max = 32) String originalCloseNo,
            @NotBlank @Size(max = 32) String currentDayNo,
            @NotBlank @Size(max = 64) String coreReference,
            @NotBlank @Size(max = 255) String reason,
            @NotBlank @Size(max = 64) String approvalRef) {}
}
