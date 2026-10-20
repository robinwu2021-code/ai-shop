package ai.neargo.shop.promotion.migration;

import lombok.Getter;
import lombok.Setter;

/** Private SQL projection preserving tombstones and orphan identity without returning a legacy bearer code. */
@Getter
@Setter
public class LegacyCouponOriginRow {
    private String instanceNo;
    private String templateNo;
    private String entityNo;
    private String instanceEntityNo;
    private String funder;
    private String rootState;
    private String migrationBatchNo;
    private Boolean rootPresent;
    private Integer instanceDeleted;
    private Integer rootDeleted;
}
