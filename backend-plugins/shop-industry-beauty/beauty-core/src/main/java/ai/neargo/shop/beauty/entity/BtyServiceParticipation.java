package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 实际服务人员参与段；保存本域事实，公共人员/资源/资金仅保存引用。存在行业共用的指派表 {@code ind_assignment}
 * （TDD-美业域迁移新库 B-4）：对象类型 {@code TASK}、对象号是服务任务号、性质 {@code ACTUAL}。字段名沿用原参与表，
 * 列名按 {@code @TableField} 对到指派表；手写 SQL 要按列名取别名（不能 {@code SELECT *}，见 {@link #COLUMNS}）。
 */
@Getter
@Setter
@TableName("ind_assignment")
public class BtyServiceParticipation extends BaseEntity {
    public static final String TARGET_TYPE = "TASK";
    public static final String KIND = "ACTUAL";
    /** 手写查询用：指派表列 → 本实体属性。表别名固定为 {@code p}。 */
    public static final String COLUMNS = "p.id, p.tenant_no, p.entity_no, p.assignment_no AS participation_no,"
            + " p.target_no AS task_no, p.personnel_no, p.employment_assignment_no AS assignment_no,"
            + " p.planned_assignment_no, p.role_code, p.hold_no AS occupation_no, p.started_at, p.ended_at,"
            + " p.previous_assignment_no AS handover_from_no, p.handover_reason AS reason,"
            + " p.skill_snapshot AS qualification_snapshot, p.target_type, p.assignment_kind, p.status,"
            + " p.created_at, p.created_by, p.updated_at, p.updated_by, p.version, p.deleted";

    /** 共用表的 {@code tenant_no} 没有库默认值，平台审计也不填它：在这里带上。 */
    public BtyServiceParticipation() {
        setTenantNo("MAIN");
    }

    /** 可信经营主体数据域。 */
    private String entityNo;
    /** 参与号（指派号）。 */
    @TableField("assignment_no")
    private String participationNo;
    /** 指派对象类型：固定 TASK。 */
    private String targetType = TARGET_TYPE;
    /** 任务（指派对象号）。 */
    @TableField("target_no")
    private String taskNo;
    /** 指派性质：实际服务。 */
    private String assignmentKind = KIND;
    /** 实际人员。 */
    private String personnelNo;
    /** 有效门店任职。 */
    @TableField("employment_assignment_no")
    private String assignmentNo;
    /** 原计划安排。 */
    private String plannedAssignmentNo;
    /** 技师或助理等业务角色。 */
    private String roleCode;
    /** 实际资源占用。 */
    @TableField("hold_no")
    private String occupationNo;
    /** 参与开始。 */
    private LocalDateTime startedAt;
    /** 参与结束。 */
    private LocalDateTime endedAt;
    /** 前一参与段。 */
    @TableField("previous_assignment_no")
    private String handoverFromNo;
    /** 交接理由。 */
    @TableField("handover_reason")
    private String reason;
    /** 开工资格与规则版本。 */
    @TableField("skill_snapshot")
    private String qualificationSnapshot;
    /** ACTIVE 进行中、ENDED 已结束。 */
    private String status = "ACTIVE";
}
