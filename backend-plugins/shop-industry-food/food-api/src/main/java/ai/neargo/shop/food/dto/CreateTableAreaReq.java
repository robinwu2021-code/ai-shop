package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Store-owned configuration only; callers cannot replace identity or resource state. */
public record CreateTableAreaReq(
        @NotBlank @Size(max=32) String code,
        @NotBlank @Size(max=64) String name,
        @NotNull Integer sortNo) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown table area parameter");
    }
}
