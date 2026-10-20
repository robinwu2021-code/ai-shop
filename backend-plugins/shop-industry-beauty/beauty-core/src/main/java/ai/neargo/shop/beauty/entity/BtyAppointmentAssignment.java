package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 预约确认人员安排历史；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_appointment_assignment")
public class BtyAppointmentAssignment extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 安排号。 */
    private String plannedAssignmentNo;
    /** 项目行。 */
    private String appointmentItemNo;
    /** 安排对应预约修订。 */
    private Long appointmentRevision;
    /** 需求。 */
    private String requirementNo;
    /** 确认人员，资源池可暂未指定。 */
    private String personnelNo;
    /** 已承诺资源或合法资源池。 */
    private String resourceNo;
    /** 持有。 */
    private String holdNo;
    /** 预计开始。 */
    private LocalDateTime startAt;
    /** 预计结束。 */
    private LocalDateTime endAt;
    /** 资格匹配依据。 */
    private String skillSnapshot;
}
