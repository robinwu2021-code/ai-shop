package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/** Mutable V1 child rows; execution history must use later immutable menu revisions. */
@Getter
@Setter
@TableName("fnb_menu_schedule")
public class FnbMenuSchedule extends BaseEntity {
    private String entityNo;
    private String menuNo;
    private String scheduleNo;
    private Integer weekday;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer endDayOffset;
}
