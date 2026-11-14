package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant transaction table; monetary authority remains in core and PAY. */
@Getter
@Setter
@TableName("fnb_core_line_allocation")
public class FnbCoreLineAllocation extends BaseEntity {
    private String entityNo;
    private String allocationNo;
    private String sourceComponentRef;
    private String foodLineNo;
    private Long orderItemId;
    private BigDecimal qty;
    private String coreSnapshotHash;
    private Long coreAmountMinor;
    private String currency;
    private String unit;
}
