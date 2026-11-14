package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 实际服务人员参与段；保存本域事实，公共人员/资源/资金仅保存引用。 */
@Getter
@Setter
@TableName("bty_service_participation")
public class BtyServiceParticipation extends BaseEntity {
    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 参与号。 */
    private String participationNo;
    /** 任务。 */
    private String taskNo;
    /** 实际人员。 */
    private String personnelNo;
    /** 有效门店任职。 */
    private String assignmentNo;
    /** 原计划安排。 */
    private String plannedAssignmentNo;
    /** 技师或助理等业务角色。 */
    private String roleCode;
    /** 实际资源占用。 */
    private String occupationNo;
    /** 参与开始。 */
    private LocalDateTime startedAt;
    /** 参与结束。 */
    private LocalDateTime endedAt;
    /** 前一参与段。 */
    private String handoverFromNo;
    /** 交接理由。 */
    private String reason;
    /** 开工资格与规则版本。 */
    private String qualificationSnapshot;
}
