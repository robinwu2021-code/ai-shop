package ai.neargo.shop.trade.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("pmt_migration_return")
public class PmtMigrationReturn extends BaseEntity {
    private String returnNo;
    private String sourceUsageNo;
    private String entityNo;
    private String orderNo;
    private String factSnapshot;
    private String status;
    private String payState;
    private String payReceiptNo;
    private LocalDateTime payAckedAt;
}
