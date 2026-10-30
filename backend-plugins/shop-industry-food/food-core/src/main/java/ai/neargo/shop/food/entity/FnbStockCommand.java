package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_stock_command")
public class FnbStockCommand extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String commandNo;
    private String action;
    private String operationKey;
    private String requestHash;
    private String status;
    private String resultJson;
    private String requestSnapshot;
}
