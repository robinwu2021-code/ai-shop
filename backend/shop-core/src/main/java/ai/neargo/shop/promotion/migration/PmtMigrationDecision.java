package ai.neargo.shop.promotion.migration;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("pmt_migration_decision")
public class PmtMigrationDecision extends BaseEntity {
    private String decisionNo;
    private String planNo;
    private String entityNo;
    private String migrationBatchNo;
    private String planHash;
    private String sourceBundleHash;
    private String planSnapshot;
    private String commandHash;
    private String requestKey;
    private String reason;
    private String actorNo;
    private LocalDateTime decidedAt;
    private String status;
}
