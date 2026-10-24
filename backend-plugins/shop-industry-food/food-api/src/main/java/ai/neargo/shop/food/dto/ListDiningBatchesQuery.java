package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Original check and effective current check are separate filters, preserving historical source identity. */
public record ListDiningBatchesQuery(@Min(1) @Max(10000) Integer page,@Min(1) @Max(100) Integer size,
        @Size(max=32) String originalCheckNo,@Size(max=32) String checkNo,@Size(max=24) String status,
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate businessDate,@Size(max=24) String orderSource) { }
