package ai.neargo.shop.pay.migration.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("ent_migration_import")
public class EntMigrationImport extends BaseEntity {
    private String importNo;
    private String planNo;
    private String entityNo;
    private String sourceDecisionNo;
    private String planHash;
    private String sourceBundleHash;
    private String proofSnapshot;
    private Long assetCount;
    private Long archivalCount;
    private Long historicalRecordCount;
    private LocalDateTime importedAt;
    private String status;
}
