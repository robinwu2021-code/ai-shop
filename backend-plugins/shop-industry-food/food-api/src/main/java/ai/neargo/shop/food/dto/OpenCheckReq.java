package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dining reception contract; monetary authority remains outside this resource. */
public record OpenCheckReq(@NotNull @Size(max=100) List<@NotBlank @Size(max=32) String> tableNos, @NotNull @Min(1) Integer partySize, @NotBlank String payMode, @Size(max=32) String reservationNo, @NotNull @Valid @Size(max=100) List<TableVersionReq> tableVersions, @NotBlank String sharingMode, @NotNull @Valid @Size(max=1000) List<SeatReq> seats, @Size(max=32) String serviceEmployeeNo) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown check parameter");
    }
    public OpenCheckReq {
        tableNos=tableNos==null ? null:Collections.unmodifiableList(new ArrayList<>(tableNos));
        tableVersions=tableVersions==null ? null:Collections.unmodifiableList(new ArrayList<>(tableVersions));
        seats=seats==null ? null:Collections.unmodifiableList(new ArrayList<>(seats));
    }
}
