package ai.neargo.shop.pay.migration.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Immutable encrypted source evidence. Never an asset, ledger or cutover decision. */
@Getter
@Setter
@TableName("ent_migration_stage")
public class EntMigrationStage extends BaseEntity {
    private String stageNo;
    private String sourceSystem;
    private String sourceTemplateNo;
    private String migrationBatchNo;
    private String entityNo;
    private String requestKey;
    private String commandHash;
    private String reason;
    private Integer sourceSchemaVersion;
    private Long frozenRevision;
    private String watermark;
    private String contentHash;
    private String cryptoKeyId;
    private String payloadCipher;
    private Integer payloadBytes;
    private Long instanceCount;
    private Long historyCount;
    private Integer manifestVersion;
    private Boolean historyAvailable;
    private String status;
}
