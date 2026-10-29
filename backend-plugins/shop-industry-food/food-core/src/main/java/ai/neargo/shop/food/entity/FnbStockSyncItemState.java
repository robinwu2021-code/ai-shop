package ai.neargo.shop.food.entity;
import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @TableName("fnb_stock_sync_item_state")
public class FnbStockSyncItemState extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String itemNo;
    private Long sourceVersion;
    private String snapshotHash;
}
