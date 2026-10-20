package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable use fact; never written through a core transaction. */
@Getter
@Setter
@TableName("ent_period_bucket")
public class EntPeriodBucket extends BaseEntity {
    private String instanceNo;
    private String periodKey;
    private LocalDate serviceDay;
    private Integer limitUnits;
    private Integer reservedUnits;
    private Integer usedUnits;
}
