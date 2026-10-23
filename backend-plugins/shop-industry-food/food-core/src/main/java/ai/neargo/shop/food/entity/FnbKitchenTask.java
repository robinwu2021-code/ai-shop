package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Restaurant fulfillment projection; financial and inventory documents retain their original owners. */
@Getter
@Setter
@TableName("fnb_kitchen_task")
public class FnbKitchenTask extends BaseEntity {
    private String entityNo;
    private String taskNo;
    private String ticketNo;
    private String foodLineNo;
    private String componentNo;
    private String stationNo;
    private Long routeVersion;
    private BigDecimal qty;
    private BigDecimal makingQty;
    private BigDecimal readyQty;
    private BigDecimal servedQty;
    private BigDecimal voidQty;
    private String status;
    private String taskPartKey;
    private String taskRole;
}
