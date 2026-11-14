package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Existing V1 closing evidence; signed rows are never overwritten by the closing service. */
@Getter
@Setter
@TableName("fnb_business_day")
public class FnbBusinessDay extends BaseEntity {
    private String entityNo;
    private String dayNo;
    private String storeNo;
    private String policyNo;
    private String status;
    private String currentCloseRefs;
    private LocalDate businessDate;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
}
