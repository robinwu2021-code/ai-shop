package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Dining reception contract; monetary authority remains outside this resource. */
public record OpenSharedCheckReq(@NotNull @Min(0) Long version, @NotNull @Min(1) Integer partySize, @NotBlank String payMode) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown check parameter");
    }
}
