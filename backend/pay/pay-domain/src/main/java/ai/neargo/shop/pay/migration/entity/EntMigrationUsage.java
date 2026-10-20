package ai.neargo.shop.pay.migration.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("ent_migration_usage")
public class EntMigrationUsage extends BaseEntity {
    private String importNo;
    private String sourceUsageNo;
    private String sourceInstanceNo;
    private String usageNo;
    private String instanceNo;
    private String sourceHash;
    private LocalDateTime sourceRevertedAt;
}
