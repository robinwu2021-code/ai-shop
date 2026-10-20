package ai.neargo.shop.promotion.migration;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Internal typed SQL projection, including tombstones; never a public entity or an import permit. */
@Getter
@Setter
public class LegacyCouponInventoryRow {
    private Long id;
    private String templateNo;
    private String entityNo;
    private Integer deleted;
    private LocalDateTime archivedAt;
    private String migrationState;
    private Long instanceCount;
    private Long deletedInstanceCount;
    private Long issueCount;
    private Long deletedIssueCount;
    private Long historyCount;
    private Long deletedHistoryCount;
    private Long peerTemplateCount;
}
