package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable use fact; never written through a core transaction. */
@Getter
@Setter
@TableName("ent_use_allocation")
public class EntUseAllocation extends BaseEntity {
    private String operationNo;
    private String instanceNo;
    private String periodKey;
    private LocalDate serviceDay;
    private Integer units;
    private Long discountMinor;
    private String currency;
    private String itemsAllocation;
    private Integer reversedUnits;
    private Long reversedDiscountMinor;
}
