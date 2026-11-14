package ai.neargo.shop.food.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("fnb_stock_sync_reconciliation")
public class FnbStockSyncReconciliation extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String reconciliationNo;
    private Long generation;
    private String operationKey;
    private String requestHash;
    private String status;
    private Long cursorId;
    private Long endId;
    private String requestSnapshot;
    private Long checkedCount;
    private Long differenceCount;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private String lastErrorCode;
}
