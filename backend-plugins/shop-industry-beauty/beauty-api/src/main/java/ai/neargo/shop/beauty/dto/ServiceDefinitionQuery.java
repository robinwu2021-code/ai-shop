package ai.neargo.shop.beauty.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Bean validation keeps invalid HTTP model attributes on the platform's normal bad-request handling path. */
public record ServiceDefinitionQuery(@Size(max = 32) String storeNo, @Size(min = 1, max = 32) String skuNo,
        OffsetDateTime at, @Min(1) Integer page, @Min(1) @Max(100) Integer size) {
    public ServiceDefinitionQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }
}
