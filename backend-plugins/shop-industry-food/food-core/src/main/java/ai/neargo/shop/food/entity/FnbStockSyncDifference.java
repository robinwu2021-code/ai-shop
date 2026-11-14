package ai.neargo.shop.food.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("fnb_stock_sync_difference")
public class FnbStockSyncDifference extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String differenceNo;
    private String reconciliationNo;
    private String itemNo;
    private Long generation;
    private Long sourceVersion;
    private Long targetVersion;
    private String differenceType;
    private String sourceSnapshot;
    private String targetSnapshot;
    private String resolutionStatus;
}
