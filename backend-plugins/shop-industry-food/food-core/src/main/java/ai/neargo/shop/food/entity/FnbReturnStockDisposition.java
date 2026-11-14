package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter @TableName("fnb_return_stock_disposition")
public class FnbReturnStockDisposition extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String operationNo;
    private String returnNo;
    private String returnItemNo;
    private String productionEventNo;
    private BigDecimal dishQty;
    private String disposition;
    private String operationKey;
    private String requestHash;
    private String requestSnapshot;
    private String nativeSnapshot;
    private String status;
    private String stockCommandNo;
    private String resultSnapshot;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String lastErrorCode;
}
