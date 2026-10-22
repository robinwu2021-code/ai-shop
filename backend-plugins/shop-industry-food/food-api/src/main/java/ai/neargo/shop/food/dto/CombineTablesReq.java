package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dining reception contract; monetary authority remains outside this resource. */
public record CombineTablesReq(@NotNull @Min(0) Long version, @NotEmpty @Valid @Size(max=100) List<TableVersionReq> tableVersions, @NotBlank @Size(max=255) String reason) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown check parameter");
    }
    public CombineTablesReq {
        tableVersions=tableVersions==null ? null:Collections.unmodifiableList(new ArrayList<>(tableVersions));
    }
}
