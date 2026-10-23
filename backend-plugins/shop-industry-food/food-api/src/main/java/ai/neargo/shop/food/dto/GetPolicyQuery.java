package ai.neargo.shop.food.dto;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/** Optional absolute instant; store business-day timezone does not reinterpret an API timestamp. */
public record GetPolicyQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) { }
