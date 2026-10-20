package ai.neargo.shop.beauty.mapper;

import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentAssignment;
import ai.neargo.shop.beauty.entity.BtyAppointmentItem;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceParticipation;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.entity.BtyServiceStep;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Contains only implemented persistence slices; immutable version rows are appended, never republished in place. */
public final class BeautyMappers {
    private BeautyMappers() {
    }

    /** Locks an authorized existing head; first creation is arbitrated by its scoped business unique key. */
    public interface StorePolicyMapper extends BaseMapper<BtyStorePolicy> {
        @Select("""
                SELECT * FROM bty_store_policy
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND policy_no=#{policyNo} AND deleted=0
                FOR UPDATE
                """)
        BtyStorePolicy selectScopedForUpdate(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("policyNo") String policyNo);

        /** Publishes only an existing version of this head; zero affected rows is a caller-visible conflict. */
        @Update("""
                UPDATE bty_store_policy
                SET current_policy_version_no=#{policyVersionNo}, version=version+1,
                    updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND policy_no=#{policyNo} AND version=#{expectedVersion} AND deleted=0
                  AND status IN ('ACTIVE', 'INACTIVE')
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                  AND EXISTS (SELECT 1 FROM bty_store_policy_version v
                    WHERE v.tenant_no='MAIN' AND v.entity_no=#{entityNo} AND v.policy_no=#{policyNo}
                      AND v.policy_version_no=#{policyVersionNo} AND v.deleted=0)
                """)
        int switchCurrentVersion(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("policyNo") String policyNo, @Param("expectedVersion") long expectedVersion,
                @Param("policyVersionNo") String policyVersionNo, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** Locks an authorized existing head; first creation is arbitrated by its scoped business unique key. */
    public interface ServiceDefinitionMapper extends BaseMapper<BtyServiceDefinition> {
        @Select("""
                SELECT * FROM bty_service_definition
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND definition_no=#{definitionNo} AND deleted=0
                FOR UPDATE
                """)
        BtyServiceDefinition selectScopedForUpdate(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("definitionNo") String definitionNo);

        /** Publishes only an existing version of this head; zero affected rows is a caller-visible conflict. */
        @Update("""
                UPDATE bty_service_definition
                SET current_definition_version_no=#{definitionVersionNo}, version=version+1,
                    updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND definition_no=#{definitionNo} AND version=#{expectedVersion} AND deleted=0
                  AND status IN ('ACTIVE', 'INACTIVE')
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                  AND EXISTS (SELECT 1 FROM bty_service_definition_version v
                    WHERE v.tenant_no='MAIN' AND v.entity_no=#{entityNo} AND v.definition_no=#{definitionNo}
                      AND v.definition_version_no=#{definitionVersionNo} AND v.deleted=0)
                """)
        int switchCurrentVersion(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("definitionNo") String definitionNo, @Param("expectedVersion") long expectedVersion,
                @Param("definitionVersionNo") String definitionVersionNo, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** Published policy snapshots are immutable; application code uses append and scoped reads only. */
    public interface StorePolicyVersionMapper extends BaseMapper<BtyStorePolicyVersion> {
    }

    /** Published execution snapshots are immutable; application code uses append and scoped reads only. */
    public interface ServiceDefinitionVersionMapper extends BaseMapper<BtyServiceDefinitionVersion> {
    }

    /** Requirements belong to an authorized definition version, checked by the publishing transaction. */
    public interface ServiceRequirementMapper extends BaseMapper<BtyServiceRequirement> {
    }
    /** Critical actions are appended with the aggregate transaction; they are not mutable operational state. */
    public interface ActionLogMapper extends BaseMapper<BtyActionLog> {
    }

    /** 预约单；业务事务先锁预约根，再处理项目及安排历史。 */
    public interface AppointmentMapper extends BaseMapper<BtyAppointment> {
        /** 显式父链范围读取；不会按当前人员反推历史安排。 */
        @Select("""
                SELECT * FROM bty_appointment WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND
                store_no=#{storeNo} AND deleted=0 AND appointment_no=#{appointmentNo} FOR UPDATE
                """)
        BtyAppointment selectScopedForUpdate(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_appointment SET start_at=#{startAt}, end_at=#{endAt},hold_no=#{holdNo},
                version=version+1, updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                appointment_no=#{appointmentNo} AND version=#{expectedVersion}
                AND status=#{expectedStatus} AND status IN ('HELD','CONFIRMED')
                AND #{startAt} IS NOT NULL AND #{endAt}> #{startAt}
                AND #{holdNo} IS NOT NULL AND LENGTH(TRIM(#{holdNo}))>0 AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int changeSchedule(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("startAt") LocalDateTime startAt,
                @Param("endAt") LocalDateTime endAt, @Param("holdNo") String holdNo,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_appointment SET status=#{targetStatus}, version=version+1, updated_by=#{actorNo},
                updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                appointment_no=#{appointmentNo} AND version=#{expectedVersion}
                AND status=#{expectedStatus}
                AND ((status='HELD' AND #{targetStatus} IN ('CANCELLED','EXPIRED'))
                OR (status='HELD' AND #{targetStatus}='CONFIRMED' AND hold_no IS NOT NULL AND
                LENGTH(TRIM(hold_no))>0)
                OR (status='CONFIRMED' AND #{targetStatus} IN ('CANCELLED','NO_SHOW')))
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int changeStatus(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_appointment SET status='CHECKED_IN', checked_in_at=#{checkedInAt}, version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                appointment_no=#{appointmentNo} AND version=#{expectedVersion}
                AND status=#{expectedStatus} AND status='CONFIRMED' AND checked_in_at IS NULL
                AND #{checkedInAt} IS NOT NULL AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND
                #{updatedAt} IS NOT NULL
                """)
        int markArrived(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("checkedInAt") LocalDateTime checkedInAt,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 预约项目行；写入前由业务事务锁定已授权预约根。 */
    public interface AppointmentItemMapper extends BaseMapper<BtyAppointmentItem> {
    }

    /** 预约确认人员安排历史；按显式安排版本追加，不覆盖旧行。 */
    public interface AppointmentAssignmentMapper extends BaseMapper<BtyAppointmentAssignment> {
        /** 显式父链范围读取；不会按当前人员反推历史安排。 */
        @Select("""
                SELECT a.* FROM bty_appointment_assignment a
                JOIN bty_appointment_item i ON i.tenant_no=a.tenant_no AND i.entity_no=a.entity_no
                AND i.appointment_item_no=a.appointment_item_no AND i.deleted=0
                JOIN bty_appointment p ON p.tenant_no=i.tenant_no AND p.entity_no=i.entity_no
                AND p.appointment_no=i.appointment_no AND p.deleted=0
                WHERE a.tenant_no='MAIN' AND a.entity_no=#{entityNo} AND a.deleted=0
                AND p.store_no=#{storeNo} AND p.appointment_no=#{appointmentNo}
                AND a.appointment_revision=#{appointmentRevision} AND #{appointmentRevision}>=0
                ORDER BY i.sequence_no, a.requirement_no, a.resource_no, a.planned_assignment_no
                """)
        List<BtyAppointmentAssignment> selectByAppointmentRevision(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("appointmentNo") String appointmentNo,
                @Param("appointmentRevision") long appointmentRevision);
    }

    /** 服务工单；调用者在本域短事务按根→任务→子行锁序操作。 */
    public interface WorkOrderMapper extends BaseMapper<BtyWorkOrder> {
        /** 显式父链范围读取；不会按当前人员反推历史安排。 */
        @Select("""
                SELECT * FROM bty_work_order WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND
                store_no=#{storeNo} AND deleted=0 AND work_order_no=#{workOrderNo} FOR UPDATE
                """)
        BtyWorkOrder selectScopedForUpdate(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_work_order SET scope_frozen=1, version=version+1, updated_by=#{actorNo},
                updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                work_order_no=#{workOrderNo} AND version=#{expectedVersion}
                AND scope_revision=#{expectedScopeRevision} AND scope_frozen=0
                AND status IN ('OPEN','IN_SERVICE','SERVICE_FINISHED') AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int freezeScope(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedScopeRevision") long expectedScopeRevision, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_work_order SET scope_revision=scope_revision+1, version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                work_order_no=#{workOrderNo} AND version=#{expectedVersion}
                AND scope_revision=#{expectedScopeRevision} AND scope_frozen=0 AND status IN ('OPEN','IN_SERVICE')
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int advanceScope(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedScopeRevision") long expectedScopeRevision, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_work_order SET status='CLOSED', closed_at=#{closedAt}, version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0 AND
                work_order_no=#{workOrderNo} AND version=#{expectedVersion}
                AND scope_revision=#{expectedScopeRevision} AND scope_frozen=1 AND status='SERVICE_FINISHED'
                AND finished_at IS NOT NULL AND #{closedAt}>=finished_at AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                AND EXISTS (SELECT 1 FROM bty_service_task t WHERE t.tenant_no='MAIN' AND t.entity_no=#{entityNo}
                AND t.work_order_no=#{workOrderNo} AND t.deleted=0)
                AND NOT EXISTS (SELECT 1 FROM bty_service_task t WHERE t.tenant_no='MAIN' AND
                t.entity_no=#{entityNo}
                AND t.work_order_no=#{workOrderNo} AND t.deleted=0
                AND (t.scope_revision>#{expectedScopeRevision} OR t.status NOT IN ('DONE','VOIDED')
                OR t.done_qty+t.voided_qty<>t.qty OR (t.status='DONE' AND (t.finished_at IS NULL OR t.done_qty<=0))
                OR (t.finished_at>bty_work_order.finished_at OR t.finished_at>#{closedAt})
                OR (t.status='VOIDED' AND (t.done_qty<>0 OR t.voided_qty<>t.qty))
                OR (t.done_qty>0 AND (t.binding_revision<=0
                OR (SELECT COALESCE(SUM(b.qty),0) FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=t.entity_no AND b.task_no=t.task_no
                AND b.binding_revision=t.binding_revision AND b.deleted=0 AND b.uom=t.uom)<>t.qty-t.voided_qty
                OR EXISTS (SELECT 1 FROM bty_trade_binding b WHERE b.tenant_no='MAIN' AND b.entity_no=t.entity_no
                AND b.task_no=t.task_no AND b.binding_revision=t.binding_revision AND b.deleted=0 AND
                b.uom<>t.uom)))
                OR (t.status='DONE' AND NOT EXISTS (SELECT 1 FROM bty_service_step present_step
                WHERE present_step.tenant_no='MAIN' AND present_step.entity_no=t.entity_no
                AND present_step.task_no=t.task_no AND present_step.deleted=0))
                OR (t.status='DONE' AND EXISTS (SELECT 1 FROM bty_service_step s WHERE s.tenant_no='MAIN'
                AND s.entity_no=t.entity_no AND s.task_no=t.task_no AND s.deleted=0 AND (s.status NOT IN
                ('DONE','SKIPPED') OR (s.status='DONE' AND s.finished_at IS NULL)
                OR s.finished_at>t.finished_at)))
                OR EXISTS (SELECT 1 FROM bty_service_participation p WHERE p.tenant_no='MAIN' AND
                p.entity_no=t.entity_no
                AND p.task_no=t.task_no AND p.deleted=0 AND (p.ended_at IS NULL OR p.ended_at>#{closedAt}))))
                """)
        int closeFrozenScope(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedScopeRevision") long expectedScopeRevision,
                @Param("closedAt") LocalDateTime closedAt, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 可独立执行服务任务；调用者在本域短事务按根→任务→子行锁序操作。 */
    public interface ServiceTaskMapper extends BaseMapper<BtyServiceTask> {
        /** 显式父链范围读取；不会按当前人员反推历史安排。 */
        @Select("""
                SELECT t.* FROM bty_service_task t JOIN bty_work_order w
                ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND w.work_order_no=t.work_order_no
                WHERE t.tenant_no='MAIN' AND t.entity_no=#{entityNo} AND t.task_no=#{taskNo} AND t.deleted=0
                AND w.store_no=#{storeNo} AND w.deleted=0 FOR UPDATE
                """)
        BtyServiceTask selectScopedForUpdate(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("taskNo") String taskNo);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_service_task SET status=#{targetStatus},
                started_at=CASE WHEN #{targetStatus}='IN_SERVICE' THEN COALESCE(started_at,#{occurredAt}) ELSE
                started_at END,
                version=version+1, updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND task_no=#{taskNo} AND deleted=0 AND
                version=#{expectedVersion} AND binding_revision=#{expectedBindingRevision}
                AND status=#{expectedStatus} AND #{occurredAt} IS NOT NULL
                AND ((status='PENDING' AND #{targetStatus}='IN_SERVICE' AND done_qty=0 AND started_at IS NULL)
                OR (status='PAUSED' AND #{targetStatus}='IN_SERVICE' AND started_at IS NOT NULL AND
                #{occurredAt}>=started_at)
                OR (status='IN_SERVICE' AND #{targetStatus}='PAUSED' AND #{occurredAt}>=started_at)
                OR (status='PENDING' AND #{targetStatus}='VOIDED' AND done_qty=0
                AND voided_qty=qty AND started_at IS NULL))
                AND EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo}
                AND w.work_order_no=bty_service_task.work_order_no AND w.deleted=0
                AND bty_service_task.scope_revision<=w.scope_revision
                AND w.status IN ('OPEN','IN_SERVICE') AND w.scope_frozen=0) AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                AND (#{targetStatus}='VOIDED' OR (qty>voided_qty AND #{expectedBindingRevision}>0
                AND #{expectedBindingRevision}>0
                AND (SELECT COALESCE(SUM(b.qty),0) FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{expectedBindingRevision} AND b.deleted=0 AND b.uom=bty_service_task.uom)
                =bty_service_task.qty-bty_service_task.voided_qty
                AND NOT EXISTS (SELECT 1 FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{expectedBindingRevision} AND b.deleted=0 AND b.uom<>bty_service_task.uom)))
                """)
        int changeStatus(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("taskNo") String taskNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedBindingRevision") long expectedBindingRevision,
                @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
                @Param("occurredAt") LocalDateTime occurredAt, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_service_task SET
                status=CASE WHEN done_qty+#{incrementQty}=qty-voided_qty THEN 'DONE' ELSE status END,
                finished_at=CASE WHEN done_qty+#{incrementQty}=qty-voided_qty THEN #{finishedAt} ELSE finished_at
                END,
                done_qty=done_qty+#{incrementQty}, version=version+1, updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND task_no=#{taskNo} AND deleted=0 AND
                version=#{expectedVersion} AND binding_revision=#{expectedBindingRevision}
                AND status='IN_SERVICE' AND #{incrementQty}>0 AND #{incrementQty}=ROUND(#{incrementQty},6)
                AND done_qty+#{incrementQty}<=qty-voided_qty
                AND started_at IS NOT NULL AND #{finishedAt}>=started_at
                AND EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo}
                AND w.work_order_no=bty_service_task.work_order_no AND w.deleted=0
                AND bty_service_task.scope_revision<=w.scope_revision
                AND w.status IN ('OPEN','IN_SERVICE') AND w.scope_frozen=0) AND #{expectedBindingRevision}>0
                AND (SELECT COALESCE(SUM(b.qty),0) FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{expectedBindingRevision} AND b.deleted=0 AND b.uom=bty_service_task.uom)
                =bty_service_task.qty-bty_service_task.voided_qty
                AND NOT EXISTS (SELECT 1 FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{expectedBindingRevision} AND b.deleted=0 AND b.uom<>bty_service_task.uom)
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                AND (done_qty+#{incrementQty}<qty-voided_qty OR (EXISTS (SELECT 1 FROM bty_service_step present_step
                WHERE present_step.tenant_no='MAIN' AND present_step.entity_no=#{entityNo}
                AND present_step.task_no=#{taskNo} AND present_step.deleted=0)
                AND NOT EXISTS (SELECT 1 FROM bty_service_step s
                WHERE s.tenant_no='MAIN' AND s.entity_no=#{entityNo} AND s.task_no=#{taskNo} AND s.deleted=0
                AND (s.status NOT IN ('DONE','SKIPPED') OR (s.status='DONE' AND s.finished_at IS NULL)
                OR s.finished_at>#{finishedAt}))))
                """)
        int recordCompletion(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("taskNo") String taskNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedBindingRevision") long expectedBindingRevision,
                @Param("incrementQty") BigDecimal incrementQty, @Param("finishedAt") LocalDateTime finishedAt,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_service_task SET binding_revision=#{newBindingRevision}, version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND task_no=#{taskNo} AND deleted=0 AND
                version=#{expectedVersion} AND binding_revision=#{expectedBindingRevision}
                AND status IN ('PENDING','IN_SERVICE','PAUSED') AND #{newBindingRevision}>binding_revision
                AND qty>voided_qty
                AND EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo}
                AND w.work_order_no=bty_service_task.work_order_no AND w.deleted=0
                AND bty_service_task.scope_revision<=w.scope_revision
                AND w.status IN ('OPEN','IN_SERVICE') AND w.scope_frozen=0) AND #{newBindingRevision}>0
                AND (SELECT COALESCE(SUM(b.qty),0) FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{newBindingRevision} AND b.deleted=0 AND b.uom=bty_service_task.uom)
                =bty_service_task.qty-bty_service_task.voided_qty
                AND NOT EXISTS (SELECT 1 FROM bty_trade_binding b
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.task_no=bty_service_task.task_no
                AND b.binding_revision=#{newBindingRevision} AND b.deleted=0 AND b.uom<>bty_service_task.uom) AND
                #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int switchBindingRevision(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("taskNo") String taskNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedBindingRevision") long expectedBindingRevision,
                @Param("newBindingRevision") long newBindingRevision, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 任务步骤实例；调用者在本域短事务按根→任务→子行锁序操作。 */
    public interface ServiceStepMapper extends BaseMapper<BtyServiceStep> {
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_service_step s
                JOIN bty_service_task t ON t.tenant_no=s.tenant_no AND t.entity_no=s.entity_no AND
                t.task_no=s.task_no
                JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND
                w.work_order_no=t.work_order_no
                LEFT JOIN bty_service_step earlier_step ON earlier_step.tenant_no=s.tenant_no AND
                earlier_step.entity_no=s.entity_no
                AND earlier_step.task_no=s.task_no AND earlier_step.deleted=0 AND
                earlier_step.sequence_no<s.sequence_no
                AND earlier_step.status NOT IN ('DONE','SKIPPED')
                SET s.status='DONE', s.finished_at=#{finishedAt}, s.result_snapshot=#{resultSnapshot},
                s.version=s.version+1, s.updated_by=#{actorNo}, s.updated_at=#{updatedAt}
                WHERE s.tenant_no='MAIN' AND s.entity_no=#{entityNo} AND s.step_no=#{stepNo} AND s.task_no=#{taskNo}
                AND s.deleted=0 AND s.version=#{expectedVersion} AND s.status='IN_PROGRESS'
                AND s.started_at IS NOT NULL AND #{finishedAt}>=s.started_at AND earlier_step.id IS NULL
                AND t.deleted=0 AND t.status='IN_SERVICE' AND t.scope_revision<=w.scope_revision
                AND w.deleted=0 AND w.store_no=#{storeNo} AND w.scope_frozen=0 AND w.status IN ('OPEN','IN_SERVICE')
                AND #{resultSnapshot} IS NOT NULL AND JSON_VALID(#{resultSnapshot}) AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int completeStep(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("stepNo") String stepNo, @Param("taskNo") String taskNo,
                @Param("expectedVersion") long expectedVersion, @Param("finishedAt") LocalDateTime finishedAt,
                @Param("resultSnapshot") String resultSnapshot, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 实际服务人员参与段；调用者在本域短事务按根→任务→子行锁序操作。 */
    public interface ServiceParticipationMapper extends BaseMapper<BtyServiceParticipation> {
        /** 本地范围/版本/前态条件不满足返回0；业务Service必须检查影响行并另验外部权威。 */
        @Update("""
                UPDATE bty_service_participation p
                JOIN bty_service_task t ON t.tenant_no=p.tenant_no AND t.entity_no=p.entity_no AND
                t.task_no=p.task_no
                JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND
                w.work_order_no=t.work_order_no
                SET p.ended_at=#{endedAt}, p.reason=#{reason}, p.version=p.version+1,
                p.updated_by=#{actorNo}, p.updated_at=#{updatedAt}
                WHERE p.tenant_no='MAIN' AND p.entity_no=#{entityNo} AND p.participation_no=#{participationNo}
                AND p.deleted=0 AND p.version=#{expectedVersion} AND p.ended_at IS NULL AND #{endedAt}>=p.started_at
                AND t.deleted=0 AND w.deleted=0 AND w.store_no=#{storeNo}
                AND w.status IN ('OPEN','IN_SERVICE','SERVICE_FINISHED') AND #{actorNo} IS NOT NULL AND
                LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int endParticipation(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("participationNo") String participationNo, @Param("expectedVersion") long expectedVersion,
                @Param("endedAt") LocalDateTime endedAt, @Param("reason") String reason,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
    }

}
