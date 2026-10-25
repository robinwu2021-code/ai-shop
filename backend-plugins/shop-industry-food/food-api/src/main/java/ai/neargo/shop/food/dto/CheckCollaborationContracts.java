package ai.neargo.shop.food.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Moves service responsibility only; the original core order and paid facts are immutable. */
public final class CheckCollaborationContracts {
    private CheckCollaborationContracts() {}

    public record CheckVersion(@NotBlank String checkNo, @Min(0) long version) {}

    public record MoveReq(
            @NotNull @Valid CheckVersion source,
            @NotNull @Valid CheckVersion target,
            @NotEmpty @Size(max = 100) List<@NotBlank String> batchNos,
            @NotBlank @Size(max = 255) String reason) {
        public MoveReq {
            if (batchNos != null) batchNos = List.copyOf(batchNos);
        }
    }

    public record MoveVO(
            String operationNo,
            String sourceCheckNo,
            String targetCheckNo,
            List<String> batchNos,
            long sourceVersion,
            long targetVersion,
            String status) {}

    public record LineMoveReq(
            @NotNull @Valid CheckVersion source,
            @NotNull @Valid CheckVersion target,
            @NotEmpty @Size(max = 100) List<@NotBlank String> foodLineNos,
            @NotBlank @Size(max = 255) String reason) {
        public LineMoveReq {
            if (foodLineNos != null) foodLineNos = List.copyOf(foodLineNos);
        }
    }

    public record LineMoveVO(
            String operationNo,
            String sourceCheckNo,
            String targetCheckNo,
            List<String> foodLineNos,
            String status) {}

    public record ReceivableBatch(
            String batchNo,
            String orderNo,
            String subOrderNo,
            String currency,
            Long originalMinor,
            Long effectiveMinor,
            Long adjustmentMinor,
            String amendmentSourceHash) {}

    public record ReceivablePreview(
            String checkNo,
            List<ReceivableBatch> batches,
            String sourceHash,
            boolean financialActionAllowed) {
        public ReceivablePreview {
            batches = List.copyOf(batches);
        }
    }

    public record FreezeReq(
            @Min(0) long checkVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceHash,
            @NotBlank @Size(max = 255) String reason) {}

    public record FreezeVO(
            String freezeNo, String checkNo, String sourceHash, String status, long version) {}
}
