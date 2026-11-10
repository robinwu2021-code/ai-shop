package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

/** Real root states and exact original-number lookup, within the authenticated current store. */
public record WorkOrderQueryReq(
        String storeNo,
        @Pattern(regexp = "OPEN|IN_SERVICE|SERVICE_FINISHED|CLOSED|CANCELLED") String status,
        @Size(max = 64) String query,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @Min(1) @Max(1000000) Integer page,
        @Min(1) @Max(100) Integer size) {
    public WorkOrderQueryReq {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }

    @AssertTrue
    @JsonIgnore
    public boolean isRangeValid() {
        return from == null || to == null || from.isBefore(to);
    }
}
