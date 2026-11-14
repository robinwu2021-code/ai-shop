package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Original quota admission fact; unique restriction/batch identity and exact decimal quantity survive retries. */
@Getter
@Setter
@TableName("fnb_sales_quota_claim")
public class FnbSalesQuotaClaim extends BaseEntity {
    private String entityNo;
    private String claimNo;
    private String restrictionNo;
    private String batchNo;
    private BigDecimal quantity;
    private String status;
}
