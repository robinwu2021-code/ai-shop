package ai.neargo.shop.beauty.dto;

import java.math.BigDecimal;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record SubmissionTaskVO(
        String taskNo,
        String skuNo,
        String definitionVersionNo,
        BigDecimal qty,
        String uom,
        long taskRevision,
        long bindingRevision,
        String sourceLineNo,
        String sourceState) {}
