package ai.neargo.shop.pay.migration.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Audited identity declaration tied to both encrypted source evidences; never a cutover permit. */
@Getter
@Setter
@TableName("ent_migration_alias")
public class EntMigrationAlias extends BaseEntity {
    private String aliasNo;
    private String entityNo;
    private String migrationBatchNo;
    private String pmtStageNo;
    private String mktStageNo;
    private String canonicalStageNo;
    private String pmtContentHash;
    private String mktContentHash;
    private String comparisonHash;
    private Long matchedInstanceCount;
    private LocalDateTime comparedAt;
    private String requestKey;
    private String commandHash;
    private String reason;
    private String status;
}
