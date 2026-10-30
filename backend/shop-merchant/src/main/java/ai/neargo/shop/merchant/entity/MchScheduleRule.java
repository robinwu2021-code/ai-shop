package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 资源周班表规则；本地开始日与UTC占用分开。 */
@Getter
@Setter
@TableName("mch_schedule_rule")
public class MchScheduleRule extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 稳定规则号。 */
    private String ruleNo;
    /** 规范资源号。 */
    private String resourceNo;
    /** 作业门店。 */
    private String storeNo;
    /** 区域或固定偏移业务时区。 */
    private String timezone;
    /** ISO星期一bit0至星期日bit6。 */
    private Integer weekdayMask;
    /** 本地开始分钟0..1439。 */
    private Integer startMinute;
    /** 本地结束分钟0..1440。 */
    private Integer endMinute;
    /** 结束日期偏移0或1。 */
    private Integer endDayOffset;
    /** 规则开始日，包含。 */
    private LocalDate validFrom;
    /** 规则最后开始日，包含且可空。 */
    private LocalDate validTo;
    /** ACTIVE或INACTIVE。 */
    private String status;
}
