package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Original restaurant restriction row; these counters never represent physical inventory. */
@Getter
@Setter
@TableName("fnb_sales_restriction")
public class FnbSalesRestriction extends BaseEntity {
    private String entityNo;
    private String restrictionNo;
    private String storeNo;
    private String skuNo;
    private String mode;
    private LocalDate businessDate;
    private BigDecimal quotaQty;
    private BigDecimal reservedQty;
    private BigDecimal consumedQty;
    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;
    private String status;
    private String reason;
    private String orderSource;
    private String fulfillment;
}
