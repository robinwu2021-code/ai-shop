package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public final class ServiceSlotContracts {
    private ServiceSlotContracts() {}

    public record CreateReq(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull Instant orderCutoffAt,
            @NotNull Instant dispatchAt,
            @NotNull @DecimalMin("0.000001") BigDecimal capacity,
            @NotBlank String unit) {}

    public record SlotVO(
            String slotNo,
            Instant startsAt,
            Instant endsAt,
            Instant orderCutoffAt,
            Instant dispatchAt,
            BigDecimal capacity,
            BigDecimal reserved,
            BigDecimal consumed,
            String unit,
            String status,
            long version) {}

    public record ClaimReq(
            @NotBlank String slotNo,
            @NotBlank String sourceNo,
            @NotNull @DecimalMin("0.000001") BigDecimal quantity) {}

    public record ClaimVO(
            String claimNo,
            String slotNo,
            String sourceNo,
            BigDecimal quantity,
            String status,
            long version) {}
}
