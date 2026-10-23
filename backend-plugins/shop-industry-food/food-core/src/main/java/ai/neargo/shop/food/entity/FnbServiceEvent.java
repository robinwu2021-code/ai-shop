package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Restaurant fulfillment projection; financial and inventory documents retain their original owners. */
@Getter
@Setter
@TableName("fnb_service_event")
public class FnbServiceEvent extends BaseEntity {
    private String entityNo;
    private String eventNo;
    private String taskNo;
    private String action;
    private BigDecimal qty;
    private String operationKey;
    private String reason;
    private String inventoryDocNo;
}
