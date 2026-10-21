package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;


/**
 * One sequential service instance (qty=1), retaining observed master revisions and actual quoted facts.
 * Master revisions do not version campaign/store price overlays. Times expose padding between instances.
 */
public record AppointmentPlanItemVO(String appointmentItemNo, String skuNo, String goodsNo, String
        definitionVersionNo, int qty, long priceMinor, int durationMin, OffsetDateTime serviceStartAt,
        OffsetDateTime serviceEndAt, Long goodsRevision, Long skuRevision) { }
