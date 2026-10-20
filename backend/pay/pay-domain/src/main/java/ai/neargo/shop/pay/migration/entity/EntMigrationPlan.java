package ai.neargo.shop.pay.migration.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Fixed encrypted target mapping; never a financial or ownership decision. */
@Getter @Setter @TableName("ent_migration_plan")
public class EntMigrationPlan extends BaseEntity {
    private String planNo;
    private String stageNo;
    private String aliasNo;
    private String sourceSystem;
    private String sourceTemplateNo;
    private String migrationBatchNo;
    private String entityNo;
    private String requestKey;
    private String commandHash;
    private String reason;
    private String planHash;
    private String sourceBundleHash;
    private String cryptoKeyId;
    private String payloadCipher;
    private Integer payloadBytes;
    private Long assetCount;
    private Long archivalCount;
    private Integer versionCount;
    private LocalDateTime assessedAt;
    private String status;
}
