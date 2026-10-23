package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** One store/business-day exception; policyNo traces the policy effective when this row was saved. */
@Getter
@Setter
@TableName("fnb_service_calendar")
public class FnbServiceCalendar extends BaseEntity {
    private String entityNo;
    private String calendarNo;
    private String storeNo;
    private LocalDate localDate;
    private String mode;
    private String intervals;
    private String policyNo;
}
