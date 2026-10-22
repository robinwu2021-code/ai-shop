package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Dining reception contract; monetary authority remains outside this resource. */
public record SeatReq(@Size(max=32) String seatNo, @NotBlank @Size(max=32) String label, @Size(max=32) String guestRef) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown check parameter");
    }
}
