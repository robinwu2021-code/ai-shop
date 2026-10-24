package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_core_order_binding")
public class FnbCoreOrderBinding extends BaseEntity {
    private String entityNo;
    private String bindingNo;
    private String batchNo;
    private String paymentRootNo;
    private String storeOrderNo;
    private String status;
}
