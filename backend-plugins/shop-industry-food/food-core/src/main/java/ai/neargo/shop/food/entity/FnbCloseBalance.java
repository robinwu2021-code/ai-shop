package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/** Existing V1 closing evidence; signed rows are never overwritten by the closing service. */
@Getter
@Setter
@TableName("fnb_close_balance")
public class FnbCloseBalance extends BaseEntity {
    private String entityNo;
    private String balanceNo;
    private String closeNo;
    private String metricCode;
    private String sourceRef;
    private Long amountMinor;
    private BigDecimal countValue;
}
