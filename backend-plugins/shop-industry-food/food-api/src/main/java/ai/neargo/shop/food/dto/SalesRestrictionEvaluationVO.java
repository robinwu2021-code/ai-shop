package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A local restriction result grants no stock availability or permission to transact. */
public record SalesRestrictionEvaluationVO(
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean blocked,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota,
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean originalStockKnown,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf,
        List<SalesRestrictionVO> restrictions) {
    public SalesRestrictionEvaluationVO { if (restrictions != null) restrictions = List.copyOf(restrictions); }
}
