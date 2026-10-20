package ai.neargo.shop.pay.migration.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("ent_migration_return")
public class EntMigrationReturn extends BaseEntity {
    private String receiptNo;
    private String returnNo;
    private String sourceUsageNo;
    private String usageNo;
    private String instanceNo;
    private String userNo;
    private String entityNo;
    private String sourceHash;
    private String factHash;
    private Integer restoredUnits;
    private Integer remainingUnits;
    private Long assetSeq;
    private LocalDateTime occurredAt;
    private String status;
}
