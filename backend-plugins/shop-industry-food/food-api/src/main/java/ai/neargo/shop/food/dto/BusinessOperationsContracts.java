package ai.neargo.shop.food.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class BusinessOperationsContracts {
    private BusinessOperationsContracts() {}

    public record Source(@NotBlank String kind, @NotBlank String sourceNo) {}

    public record Evidence(
            Source source,
            String status,
            long version,
            String sourceHash,
            boolean terminal,
            String currentOwner) {}

    public record HandoverReq(
            @NotBlank String receiverNo,
            @NotEmpty @Size(max = 100) List<@Valid Source> sources,
            @NotBlank @Size(max = 255) String reason) {
        public HandoverReq {
            if (sources != null) sources = List.copyOf(sources);
        }
    }

    public record HandoverVO(
            String handoverNo,
            String fromActor,
            String receiverNo,
            String status,
            String sourceHash,
            long version) {}

    public record CaseReq(
            @NotNull @Valid Source source,
            @NotBlank String ownerNo,
            @NotBlank @Size(max = 255) String reason) {}

    public record CaseVO(
            String caseNo,
            Source source,
            String ownerNo,
            String status,
            String sourceHash,
            String resolutionHash,
            long version) {}

    public record DecisionReq(@Min(0) long version, @NotBlank @Size(max = 255) String reason) {}
}
