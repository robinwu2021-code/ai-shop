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
public record TransferCheckReq(@NotNull @Min(0) Long version, @NotBlank @Size(max=255) String reason, @Size(max=64) String approvalRef, @NotEmpty @Size(max=100) List<@NotBlank @Size(max=32) String> targetTableNos, @NotEmpty @Valid @Size(max=200) List<TableVersionReq> tableVersions) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown check parameter");
    }
    public TransferCheckReq {
        tableVersions=tableVersions==null ? null:Collections.unmodifiableList(new ArrayList<>(tableVersions));
        targetTableNos=targetTableNos==null ? null:Collections.unmodifiableList(new ArrayList<>(targetTableNos));
    }
}
