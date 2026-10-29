package ai.neargo.shop.food.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("fnb_stock_sync_delivery")
public class FnbStockSyncDelivery extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String syncNo;
    private String eventNo;
    private String itemNo;
    private Long generation;
    private Long sourceVersion;
    private String payloadHash;
    private String operationKey;
    private String payloadSnapshot;
    private String status;
    private Integer attempts;
    private java.time.LocalDateTime nextAttemptAt;
    private String lastErrorCode;
    private String ackRef;
}
