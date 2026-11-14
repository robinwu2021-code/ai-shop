package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_stock_item")
public class FnbStockItem extends BaseEntity {
    private String entityNo;
    private String itemNo;
    private String itemKind;
    private Integer weighed;
    private String skuNo;
    private String name;
    private String baseUnit;
    private String trackingMode;
    private String status;
}
