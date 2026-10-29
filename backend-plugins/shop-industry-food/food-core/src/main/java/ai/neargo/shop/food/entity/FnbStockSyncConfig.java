package ai.neargo.shop.food.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("fnb_stock_sync_config")
public class FnbStockSyncConfig extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String targetSystem;
    private String status;
    private Long generation;
    private Long baselineCursor;
    private Long baselineEndId;
    private java.time.LocalDateTime nextAttemptAt;
    private String lastErrorCode;
    private java.time.LocalDateTime enabledAt;
    private java.time.LocalDateTime disabledAt;
}
