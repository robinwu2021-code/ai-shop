package ai.neargo.shop.food.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ReservationPreorderContracts {
    private ReservationPreorderContracts() {}

    public record SaveReq(
            @Min(0) long reservationVersion,
            @NotNull Instant cutoffAt,
            @NotEmpty @Size(max = 100) List<@Valid OrderLineInput> lines) {
        public SaveReq {
            if (lines != null) lines = List.copyOf(lines);
        }
    }

    public record PreorderVO(
            String preorderNo,
            String reservationNo,
            String status,
            Instant cutoffAt,
            List<OrderLineInput> lines,
            String cartNo,
            String batchNo,
            long version) {}

    public record ConvertReq(
            @Min(0) long version, @NotNull @Valid CreateStaffDiningOrderReq order) {}
}
