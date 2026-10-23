package ai.neargo.shop.beauty.mapper;

import ai.neargo.shop.beauty.entity.BtyActionLog;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentAssignment;
import ai.neargo.shop.beauty.entity.BtyAppointmentCurrent;
import ai.neargo.shop.beauty.entity.BtyAppointmentItem;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentPreparation;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceDecision;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceFence;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceIntent;
import ai.neargo.shop.beauty.entity.BtyArrivalChallenge;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceParticipation;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.entity.BtyServiceStep;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtySourceAllocationGuard;
import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
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
        /** Conversion advances only the revision; execution still has not started. */
        @Update("""
                UPDATE bty_appointment SET version=version+1,updated_by=#{actorNo},updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                AND appointment_no=#{appointmentNo} AND deleted=0 AND version=#{expectedVersion}
                AND status='CHECKED_IN' AND checked_in_at IS NOT NULL
                AND EXISTS (SELECT 1 FROM bty_appointment_current c WHERE c.tenant_no=bty_appointment.tenant_no
                  AND c.entity_no=bty_appointment.entity_no AND c.appointment_no=bty_appointment.appointment_no
                  AND c.deleted=0 AND c.operation_no IS NULL)
                AND EXISTS (SELECT 1 FROM bty_appointment_resource_fence f WHERE f.tenant_no=bty_appointment.tenant_no
                  AND f.entity_no=bty_appointment.entity_no AND f.appointment_no=bty_appointment.appointment_no
                  AND f.deleted=0 AND f.active_intent_no IS NULL)
                AND NOT EXISTS (SELECT 1 FROM bty_work_order w WHERE w.tenant_no=bty_appointment.tenant_no
                  AND w.entity_no=bty_appointment.entity_no AND w.appointment_no=bty_appointment.appointment_no)
                """)
        int markWorkOrderOpened(@Param("entityNo") String entityNo,@Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo,@Param("expectedVersion") long expectedVersion,
                @Param("actorNo") String actorNo,@Param("updatedAt") LocalDateTime updatedAt);

        /** Existing order roots forbid reception replacement even before actual task execution. */
        @Select("""
                SELECT (SELECT COUNT(*) FROM bty_work_order w WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo}
                AND w.store_no=#{storeNo} AND w.appointment_no=#{appointmentNo} AND w.deleted=0)
                + (SELECT COUNT(*) FROM bty_service_task t JOIN bty_appointment_item a
                ON a.tenant_no=t.tenant_no AND a.entity_no=t.entity_no
                AND a.appointment_item_no=t.appointment_item_no AND a.deleted=0
                WHERE a.tenant_no='MAIN' AND a.entity_no=#{entityNo} AND a.appointment_no=#{appointmentNo}
                AND t.deleted=0)
                """)
        long countWorkOrders(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo);
        /** Parent scope is checked before exposing affected task references. */
        @Select("""
                SELECT t.task_no FROM bty_service_task t JOIN bty_work_order w
                ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND w.work_order_no=t.work_order_no
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo}
                AND w.appointment_no=#{appointmentNo} AND w.deleted=0 AND t.deleted=0 ORDER BY t.id
                """)
        List<String> affectedTaskNos(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo);

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
                AND NOT EXISTS (SELECT 1 FROM bty_appointment_resource_fence f
                  WHERE f.tenant_no=bty_appointment.tenant_no AND f.entity_no=bty_appointment.entity_no
                  AND f.appointment_no=bty_appointment.appointment_no AND f.active_intent_no IS NOT NULL)
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
                AND NOT EXISTS (SELECT 1 FROM bty_appointment_resource_fence f
                  WHERE f.tenant_no=bty_appointment.tenant_no AND f.entity_no=bty_appointment.entity_no
                  AND f.appointment_no=bty_appointment.appointment_no AND f.active_intent_no IS NOT NULL)
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
                AND NOT EXISTS (SELECT 1 FROM bty_appointment_resource_fence f
                  WHERE f.tenant_no=bty_appointment.tenant_no AND f.entity_no=bty_appointment.entity_no
                  AND f.appointment_no=bty_appointment.appointment_no AND f.active_intent_no IS NOT NULL)
                AND status=#{expectedStatus} AND status='CONFIRMED' AND checked_in_at IS NULL
                AND #{checkedInAt} IS NOT NULL AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND
                #{updatedAt} IS NOT NULL
                """)
        int markArrived(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("checkedInAt") LocalDateTime checkedInAt,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 永久预约根栅栏；调用者事务先锁预约，再锁栅栏及意图。禁止按TTL释放。 */
    public interface AppointmentResourceFenceMapper extends BaseMapper<BtyAppointmentResourceFence> {
        @Select("""
                SELECT * FROM bty_appointment_resource_fence
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND appointment_no=#{appointmentNo} AND deleted=0 FOR UPDATE
                """)
        BtyAppointmentResourceFence selectScopedForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("appointmentNo") String appointmentNo);

        /** 持久化原语：不校验操作者权限，不发放业务许可。 */
        @Update("""
                UPDATE bty_appointment_resource_fence f
                JOIN bty_appointment p ON p.tenant_no=f.tenant_no AND p.entity_no=f.entity_no
                  AND p.store_no=f.store_no AND p.appointment_no=f.appointment_no AND p.deleted=0
                JOIN bty_appointment_resource_intent i ON i.tenant_no=f.tenant_no AND i.entity_no=f.entity_no
                  AND i.store_no=f.store_no AND i.appointment_no=f.appointment_no AND i.deleted=0
                SET f.active_intent_no=i.intent_no, f.version=f.version+1,
                    f.updated_by=#{actorNo}, f.updated_at=#{updatedAt}
                WHERE f.tenant_no='MAIN' AND f.entity_no=#{entityNo} AND f.store_no=#{storeNo}
                  AND f.appointment_no=#{appointmentNo} AND f.deleted=0 AND f.active_intent_no IS NULL
                  AND f.version=#{expectedFenceVersion} AND f.version<9223372036854775807
                  AND i.intent_no=#{intentNo} AND i.phase_code=0 AND i.source_revision=p.version
                  AND (p.status IN ('HELD','CONFIRMED') OR (p.status='CHECKED_IN' AND i.action='REPLACE'
                    AND NOT EXISTS (SELECT 1 FROM bty_work_order w WHERE w.tenant_no=p.tenant_no
                      AND w.entity_no=p.entity_no AND w.store_no=p.store_no
                      AND w.appointment_no=p.appointment_no AND w.deleted=0)
                    AND NOT EXISTS (SELECT 1 FROM bty_service_task t JOIN bty_appointment_item a
                      ON a.tenant_no=t.tenant_no AND a.entity_no=t.entity_no
                      AND a.appointment_item_no=t.appointment_item_no AND a.deleted=0
                      WHERE a.tenant_no=p.tenant_no AND a.entity_no=p.entity_no
                      AND a.appointment_no=p.appointment_no AND t.deleted=0)
                    AND EXISTS (SELECT 1 FROM bty_appointment_current c JOIN bty_appointment_operation o
                      ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.appointment_no=c.appointment_no
                      AND o.operation_no=c.operation_no AND o.deleted=0
                      WHERE c.tenant_no=p.tenant_no AND c.entity_no=p.entity_no
                      AND c.appointment_no=p.appointment_no AND c.deleted=0 AND o.store_no=p.store_no
                      AND o.action='RECEPTION_CHANGE' AND o.state='PENDING')))
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int acquire(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("intentNo") String intentNo,
                @Param("expectedFenceVersion") long expectedFenceVersion, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);

        /** 仅允许已对账的活动意图置空；终态权威证据由未来来源Service核验。 */
        @Update("""
                UPDATE bty_appointment_resource_fence f
                JOIN bty_appointment_resource_intent i ON i.tenant_no=f.tenant_no AND i.entity_no=f.entity_no
                  AND i.store_no=f.store_no AND i.appointment_no=f.appointment_no
                  AND i.intent_no=f.active_intent_no AND i.deleted=0
                JOIN bty_appointment p ON p.tenant_no=f.tenant_no AND p.entity_no=f.entity_no
                  AND p.store_no=f.store_no AND p.appointment_no=f.appointment_no AND p.deleted=0
                SET f.active_intent_no=NULL, f.version=f.version+1,
                    f.updated_by=#{actorNo}, f.updated_at=#{updatedAt}
                WHERE f.tenant_no='MAIN' AND f.entity_no=#{entityNo} AND f.store_no=#{storeNo}
                  AND f.appointment_no=#{appointmentNo} AND f.deleted=0 AND f.active_intent_no=#{intentNo}
                  AND f.version=#{expectedFenceVersion} AND f.version<9223372036854775807
                  AND i.phase_code=3 AND i.terminal_code IN (1,2) AND i.terminal_hash IS NOT NULL
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int releaseReconciled(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("appointmentNo") String appointmentNo, @Param("intentNo") String intentNo,
                @Param("expectedFenceVersion") long expectedFenceVersion, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);
    }

    /** 原始计划与来源决定持久化；不能作为资源授权或回执真实性的证明。 */
    public interface AppointmentResourceIntentMapper extends BaseMapper<BtyAppointmentResourceIntent> {
        @Select("""
                SELECT * FROM bty_appointment_resource_intent
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND command_no=#{commandNo} AND deleted=0 FOR UPDATE
                """)
        BtyAppointmentResourceIntent selectByCommandForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("commandNo") String commandNo);

        /** 来源Service先验实际权限及计划；该方法只负责CAS保存决定。 */
        @Update("""
                UPDATE bty_appointment_resource_intent i
                JOIN bty_appointment_resource_fence f ON f.tenant_no=i.tenant_no AND f.entity_no=i.entity_no
                  AND f.store_no=i.store_no AND f.appointment_no=i.appointment_no
                  AND f.active_intent_no=i.intent_no AND f.deleted=0
                JOIN bty_appointment p ON p.tenant_no=i.tenant_no AND p.entity_no=i.entity_no
                  AND p.store_no=i.store_no AND p.appointment_no=i.appointment_no AND p.deleted=0
                SET i.phase_code=1, i.decision_no=#{decisionNo}, i.issued_at=#{issuedAt},
                    i.valid_until=#{validUntil}, i.version=i.version+1,
                    i.updated_by=#{actorNo}, i.updated_at=#{updatedAt}
                WHERE i.tenant_no='MAIN' AND i.entity_no=#{entityNo} AND i.store_no=#{storeNo}
                  AND i.intent_no=#{intentNo} AND i.deleted=0 AND i.phase_code=0
                  AND i.version=#{expectedVersion} AND i.version<9223372036854775807
                  AND i.source_revision=p.version AND (p.status IN ('HELD',
                          'CONFIRMED') OR (p.status='CHECKED_IN' AND i.action='REPLACE'
                    AND NOT EXISTS (SELECT 1 FROM bty_work_order w WHERE w.tenant_no=p.tenant_no
                      AND w.entity_no=p.entity_no AND w.store_no=p.store_no
                      AND w.appointment_no=p.appointment_no AND w.deleted=0)
                    AND NOT EXISTS (SELECT 1 FROM bty_service_task t JOIN bty_appointment_item a
                      ON a.tenant_no=t.tenant_no AND a.entity_no=t.entity_no
                      AND a.appointment_item_no=t.appointment_item_no AND a.deleted=0
                      WHERE a.tenant_no=p.tenant_no AND a.entity_no=p.entity_no
                      AND a.appointment_no=p.appointment_no AND t.deleted=0)
                    AND EXISTS (SELECT 1 FROM bty_appointment_current c JOIN bty_appointment_operation o
                      ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.appointment_no=c.appointment_no
                      AND o.operation_no=c.operation_no AND o.deleted=0
                      WHERE c.tenant_no=p.tenant_no AND c.entity_no=p.entity_no
                      AND c.appointment_no=p.appointment_no AND c.deleted=0 AND o.store_no=p.store_no
                      AND o.action='RECEPTION_CHANGE' AND o.state='PENDING')))
                  AND #{decisionNo} IS NOT NULL AND LENGTH(TRIM(#{decisionNo}))>0
                  AND #{issuedAt} IS NOT NULL AND #{validUntil}>#{issuedAt}
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int authorize(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("intentNo") String intentNo, @Param("expectedVersion") long expectedVersion,
                @Param("decisionNo") String decisionNo, @Param("issuedAt") LocalDateTime issuedAt,
                @Param("validUntil") LocalDateTime validUntil, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);

        /** 来源Service核验可信命令终态后调用；传入终态参数本身不构成权威证据。 */
        @Update("""
                UPDATE bty_appointment_resource_intent i
                JOIN bty_appointment_resource_fence f ON f.tenant_no=i.tenant_no AND f.entity_no=i.entity_no
                  AND f.store_no=i.store_no AND f.appointment_no=i.appointment_no
                  AND f.active_intent_no=i.intent_no AND f.deleted=0
                JOIN bty_appointment p ON p.tenant_no=i.tenant_no AND p.entity_no=i.entity_no
                  AND p.store_no=i.store_no AND p.appointment_no=i.appointment_no AND p.deleted=0
                SET i.phase_code=3, i.terminal_code=#{terminalCode}, i.terminal_hash=#{terminalHash},
                    i.version=i.version+1, i.updated_by=#{actorNo}, i.updated_at=#{updatedAt}
                WHERE i.tenant_no='MAIN' AND i.entity_no=#{entityNo} AND i.store_no=#{storeNo}
                  AND i.intent_no=#{intentNo} AND i.deleted=0 AND i.phase_code IN (1,2)
                  AND i.version=#{expectedVersion} AND i.version<9223372036854775807
                  AND (i.reason_code IS NULL OR #{terminalCode}=2)
                  AND #{terminalCode} IN (1,2) AND #{terminalHash} IS NOT NULL AND LENGTH(#{terminalHash})=64
                  AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int reconcile(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("intentNo") String intentNo, @Param("expectedVersion") long expectedVersion,
                @Param("terminalCode") int terminalCode, @Param("terminalHash") String terminalHash,
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
        /** Current scope is part of SQL authorization, including canonical root/context encoding. */
        @Select("""
                SELECT w.* FROM bty_work_order w WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo}
                AND w.store_no=#{storeNo} AND w.work_order_no=#{workOrderNo} AND w.deleted=0
                AND (NOT EXISTS (SELECT 1 FROM bty_work_order_context any_context
                  WHERE any_context.tenant_no=w.tenant_no AND any_context.work_order_no=w.work_order_no)
                  OR EXISTS (SELECT 1 FROM bty_work_order_context c WHERE c.tenant_no=w.tenant_no
                    AND c.entity_no=w.entity_no AND c.store_no=w.store_no AND c.work_order_no=w.work_order_no
                    AND c.deleted=0 AND w.customer_ref=CONCAT(c.identity_kind,':',c.identity_ref)))
                """)
        BtyWorkOrder selectScoped(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo);
        /** Consumer ownership is proved before any child read, never by external mutable guest state. */
        @Select("""
                SELECT w.* FROM bty_work_order w JOIN bty_work_order_context c
                  ON c.tenant_no=w.tenant_no AND c.entity_no=w.entity_no AND c.store_no=w.store_no
                  AND c.work_order_no=w.work_order_no AND c.deleted=0
                  AND w.customer_ref=CONCAT(c.identity_kind,':',c.identity_ref)
                WHERE w.tenant_no='MAIN' AND w.work_order_no=#{workOrderNo} AND w.deleted=0
                  AND ((c.identity_kind='USER' AND c.identity_ref=#{userNo})
                  OR (c.identity_kind='GUEST' AND EXISTS (SELECT 1 FROM bty_guest_claim_receipt r
                    WHERE r.tenant_no=w.tenant_no AND r.entity_no=w.entity_no AND r.store_no=w.store_no
                    AND r.guest_no=c.identity_ref AND r.user_no=#{userNo} AND r.deleted=0)))
                """)
        BtyWorkOrder selectOwned(@Param("userNo") String userNo, @Param("workOrderNo") String workOrderNo);

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
        /** Every child proves the complete live root and optional original appointment-item parent. */
        @Select("""
                SELECT t.* FROM bty_service_task t JOIN bty_work_order w
                  ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND w.work_order_no=t.work_order_no
                WHERE t.tenant_no='MAIN' AND t.entity_no=#{entityNo} AND t.work_order_no=#{workOrderNo}
                  AND t.deleted=0 AND w.deleted=0 AND w.store_no=#{storeNo}
                  AND t.scope_revision>0 AND t.scope_revision<=w.scope_revision
                  AND (t.appointment_item_no IS NULL OR EXISTS (SELECT 1 FROM bty_appointment_item i
                    WHERE i.tenant_no=t.tenant_no AND i.entity_no=t.entity_no
                    AND i.appointment_item_no=t.appointment_item_no AND i.appointment_no=w.appointment_no
                    AND i.deleted=0)) ORDER BY t.id
                """)
        List<BtyServiceTask> selectForWorkOrder(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo);

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
        /** A task number alone cannot authorize rows from a foreign or deleted parent. */
        @Select("""
                SELECT c.* FROM bty_service_step c JOIN bty_service_task t
                  ON t.tenant_no=c.tenant_no AND t.entity_no=c.entity_no AND t.task_no=c.task_no
                JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no
                  AND w.work_order_no=t.work_order_no
                WHERE c.tenant_no='MAIN' AND c.entity_no=#{entityNo} AND c.task_no=#{taskNo} AND c.deleted=0
                  AND t.work_order_no=#{workOrderNo} AND t.deleted=0 AND w.deleted=0 AND w.store_no=#{storeNo}
                  AND t.scope_revision>0 AND t.scope_revision<=w.scope_revision
                  AND (t.appointment_item_no IS NULL OR EXISTS (SELECT 1 FROM bty_appointment_item i
                    WHERE i.tenant_no=t.tenant_no AND i.entity_no=t.entity_no
                    AND i.appointment_item_no=t.appointment_item_no AND i.appointment_no=w.appointment_no
                    AND i.deleted=0)) ORDER BY c.id
                """)
        List<BtyServiceStep> selectForTask(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo, @Param("taskNo") String taskNo);

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
        /** A task number alone cannot authorize rows from a foreign or deleted parent. */
        @Select("""
                SELECT c.* FROM bty_service_participation c JOIN bty_service_task t
                  ON t.tenant_no=c.tenant_no AND t.entity_no=c.entity_no AND t.task_no=c.task_no
                JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no
                  AND w.work_order_no=t.work_order_no
                WHERE c.tenant_no='MAIN' AND c.entity_no=#{entityNo} AND c.task_no=#{taskNo} AND c.deleted=0
                  AND t.work_order_no=#{workOrderNo} AND t.deleted=0 AND w.deleted=0 AND w.store_no=#{storeNo}
                  AND t.scope_revision>0 AND t.scope_revision<=w.scope_revision
                  AND (t.appointment_item_no IS NULL OR EXISTS (SELECT 1 FROM bty_appointment_item i
                    WHERE i.tenant_no=t.tenant_no AND i.entity_no=t.entity_no
                    AND i.appointment_item_no=t.appointment_item_no AND i.appointment_no=w.appointment_no
                    AND i.deleted=0)) ORDER BY c.id
                """)
        List<BtyServiceParticipation> selectForTask(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo, @Param("taskNo") String taskNo);

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


    /** 提交与恢复意图；外部权威结果由业务Service验证，不持锁调用外部。 */
    public interface ServiceSubmissionMapper extends BaseMapper<BtyServiceSubmission> {
        /** Only a real OPEN draft belonging to this authorized live root is projected. */
        @Select("""
                SELECT s.* FROM bty_service_submission s JOIN bty_work_order w
                  ON w.tenant_no=s.tenant_no AND w.entity_no=s.entity_no AND w.store_no=s.store_no
                  AND w.work_order_no=s.work_order_no
                WHERE s.tenant_no='MAIN' AND s.entity_no=#{entityNo} AND s.store_no=#{storeNo}
                  AND s.work_order_no=#{workOrderNo} AND s.action='OPEN' AND s.deleted=0 AND w.deleted=0
                """)
        BtyServiceSubmission selectOpenForWorkOrder(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("workOrderNo") String workOrderNo);

        /** 只读取已授权本地事实；不存在的行不提供创建互斥。 */
        @Select("""
                SELECT * FROM bty_service_submission WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND store_no=#{storeNo} AND request_key_hash=#{requestKeyHash} AND deleted=0
                AND (work_order_no IS NULL OR EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo} AND w.deleted=0
                AND w.work_order_no=bty_service_submission.work_order_no))
                """)
        BtyServiceSubmission selectByRequestKey(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("requestKeyHash") String requestKeyHash);

        /** 只读取已授权本地事实；不存在的行不提供创建互斥。 */
        @Select("""
                SELECT * FROM bty_service_submission WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND store_no=#{storeNo} AND submission_no=#{submissionNo} AND deleted=0
                AND (work_order_no IS NULL OR EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo} AND w.deleted=0
                AND w.work_order_no=bty_service_submission.work_order_no))
                FOR UPDATE
                """)
        BtyServiceSubmission selectScopedForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("submissionNo") String submissionNo);

        /** 只读取已授权本地事实；不存在的行不提供创建互斥。 */
        @Select("""
                <script>
                SELECT * FROM bty_service_submission WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND store_no=#{storeNo} AND deleted=0 AND status IN ('RECEIVED','SOURCE_PENDING','RECOVERING')
                AND next_retry_at IS NOT NULL AND next_retry_at &lt;= #{now}
                AND (work_order_no IS NULL OR EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo} AND w.deleted=0
                AND w.work_order_no=bty_service_submission.work_order_no))
                ORDER BY next_retry_at, id
                LIMIT <choose><when test="limit &gt; 0 and limit &lt;=
                100">#{limit}</when><otherwise>0</otherwise></choose>
                FOR UPDATE SKIP LOCKED
                </script>
                """)
        List<BtyServiceSubmission> selectDueForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("now") LocalDateTime now, @Param("limit") int limit);

        /** 范围或版本不符返回0；标量SQL显式写审计，Service须检查结果与外部证明。 */
        @Update("""
                UPDATE bty_service_submission SET status=#{targetStatus}, next_retry_at=#{nextRetryAt},
                failure_code=#{failureCode}, version=version+1, updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo} AND deleted=0
                AND submission_no=#{submissionNo} AND version=#{expectedVersion} AND status=#{expectedStatus}
                AND ((status='RECEIVED' AND #{targetStatus} IN ('RECEIVED','SOURCE_PENDING','REJECTED'))
                OR (status='SOURCE_PENDING' AND #{targetStatus} IN
                ('SOURCE_PENDING','RECOVERING','BOUND','REJECTED'))
                OR (status='RECOVERING' AND #{targetStatus} IN
                ('RECOVERING','SOURCE_PENDING','BOUND','REJECTED')))
                AND ((#{targetStatus} IN ('RECEIVED','SOURCE_PENDING','RECOVERING') AND
                CAST(#{nextRetryAt} AS DATETIME(3))>CAST(#{updatedAt} AS DATETIME(3)))
                OR (#{targetStatus} IN ('BOUND','REJECTED') AND #{nextRetryAt} IS NULL))
                AND (#{targetStatus}<>'REJECTED' OR (#{failureCode} IS NOT NULL AND
                LENGTH(TRIM(#{failureCode}))>0))
                AND (#{targetStatus}<>'BOUND' OR #{failureCode} IS NULL)
                AND (work_order_no IS NULL OR EXISTS (SELECT 1 FROM bty_work_order w
                WHERE w.tenant_no='MAIN' AND w.entity_no=#{entityNo} AND w.store_no=#{storeNo} AND w.deleted=0
                AND w.work_order_no=bty_service_submission.work_order_no))
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int advanceProgress(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("submissionNo") String submissionNo, @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
                @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("failureCode") String failureCode,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);

    }

    /** 不可变来源绑定历史；从双父链确认范围，按版本批读。 */
    public interface TradeBindingMapper extends BaseMapper<BtyTradeBinding> {
        /** 只读取已授权本地事实；不存在的行不提供创建互斥。 */
        @Select("""
                <script>
                SELECT b.* FROM bty_trade_binding b
                JOIN bty_service_task t ON t.tenant_no=b.tenant_no AND t.entity_no=b.entity_no AND
                t.task_no=b.task_no
                JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND
                w.work_order_no=t.work_order_no
                JOIN bty_service_submission s ON s.tenant_no=b.tenant_no AND s.entity_no=b.entity_no
                AND s.submission_no=b.submission_no
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.deleted=0 AND t.deleted=0 AND
                w.deleted=0
                AND s.deleted=0 AND w.store_no=#{storeNo} AND s.store_no=#{storeNo}
                AND (s.work_order_no IS NULL OR s.work_order_no=w.work_order_no) AND
                b.binding_revision=#{bindingRevision}
                <choose><when test="taskNos != null and !taskNos.isEmpty()">
                AND b.task_no IN <foreach collection="taskNos" item="task" open="(" separator=","
                close=")">#{task}</foreach>
                </when><otherwise>AND 1=0</otherwise></choose>
                ORDER BY b.task_no, b.binding_no
                </script>
                """)
        List<BtyTradeBinding> selectByTaskRevisions(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("taskNos") List<String> taskNos,
                @Param("bindingRevision") long bindingRevision);

    }

    /** 主体内规范来源行仲裁；涉及来源先锁guard，再锁工单与任务。 */
    public interface SourceAllocationGuardMapper extends BaseMapper<BtySourceAllocationGuard> {
        /** 只读取已授权本地事实；不存在的行不提供创建互斥。 */
        @Select("""
                SELECT * FROM bty_source_allocation_guard WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND source_kind=#{sourceKind} AND source_no=#{sourceNo} AND source_line_ref=#{sourceLineRef}
                AND deleted=0 FOR UPDATE
                """)
        BtySourceAllocationGuard selectSourceForUpdate(@Param("entityNo") String entityNo,
                @Param("sourceKind") String sourceKind, @Param("sourceNo") String sourceNo,
                @Param("sourceLineRef") String sourceLineRef);

        /** 范围或版本不符返回0；标量SQL显式写审计，Service须检查结果与外部证明。 */
        @Update("""
                UPDATE bty_source_allocation_guard SET allocated_qty=allocated_qty+#{incrementQty},
                version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND guard_no=#{guardNo} AND deleted=0
                AND version=#{expectedVersion} AND source_revision=#{expectedSourceRevision}
                AND #{incrementQty}>0 AND #{incrementQty}=ROUND(#{incrementQty},6)
                AND allocated_qty+#{incrementQty}<=source_qty
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int allocateQuantity(@Param("entityNo") String entityNo, @Param("guardNo") String guardNo,
                @Param("expectedVersion") long expectedVersion,
                @Param("expectedSourceRevision") long expectedSourceRevision,
                @Param("incrementQty") BigDecimal incrementQty, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);

        /** 范围或版本不符返回0；标量SQL显式写审计，Service须检查结果与外部证明。 */
        @Update("""
                UPDATE bty_source_allocation_guard SET allocated_qty=allocated_qty-#{releaseQty},
                version=version+1,
                updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND guard_no=#{guardNo} AND deleted=0
                AND version=#{expectedVersion} AND #{releaseQty}>0 AND #{releaseQty}=ROUND(#{releaseQty},6)
                AND #{releaseQty}<=allocated_qty
                AND NOT EXISTS (SELECT 1 FROM bty_trade_binding b
                LEFT JOIN bty_service_task t ON t.tenant_no=b.tenant_no AND t.entity_no=b.entity_no AND
                t.task_no=b.task_no
                LEFT JOIN bty_work_order w ON w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no
                AND w.work_order_no=t.work_order_no
                LEFT JOIN bty_service_submission s ON s.tenant_no=b.tenant_no AND s.entity_no=b.entity_no
                AND s.submission_no=b.submission_no
                WHERE b.tenant_no='MAIN' AND b.entity_no=#{entityNo} AND b.guard_no=#{guardNo}
                AND (t.id IS NULL OR w.id IS NULL OR s.id IS NULL OR t.deleted<>0 OR w.deleted<>0 OR s.deleted<>0
                OR b.source_kind<>bty_source_allocation_guard.source_kind
                OR b.source_no<>bty_source_allocation_guard.source_no
                OR b.source_line_ref<>bty_source_allocation_guard.source_line_ref OR
                b.uom<>bty_source_allocation_guard.uom
                OR b.source_revision>bty_source_allocation_guard.source_revision
                OR w.store_no<>s.store_no OR (s.work_order_no IS NOT NULL AND s.work_order_no<>w.work_order_no)
                OR t.status<>'VOIDED' OR t.done_qty<>0 OR t.voided_qty<>t.qty OR t.started_at IS NOT NULL
                OR s.status NOT IN ('BOUND','REJECTED')))
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int releaseUnconsumedQuantity(@Param("entityNo") String entityNo, @Param("guardNo") String guardNo,
                @Param("expectedVersion") long expectedVersion, @Param("releaseQty") BigDecimal releaseQty,
                @Param("actorNo") String actorNo, @Param("updatedAt") LocalDateTime updatedAt);

        /** 范围或版本不符返回0；标量SQL显式写审计，Service须检查结果与外部证明。 */
        @Update("""
                UPDATE bty_source_allocation_guard SET source_revision=#{newSourceRevision},
                source_qty=#{newSourceQty},
                source_hash=#{sourceHash}, version=version+1, updated_by=#{actorNo}, updated_at=#{updatedAt}
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND guard_no=#{guardNo} AND deleted=0
                AND version=#{expectedVersion} AND source_revision=#{expectedSourceRevision}
                AND #{newSourceRevision}>source_revision AND #{newSourceQty}>=allocated_qty
                AND #{newSourceQty}=ROUND(#{newSourceQty},6) AND #{sourceHash} IS NOT NULL AND
                LENGTH(#{sourceHash})=64
                AND #{actorNo} IS NOT NULL AND LENGTH(TRIM(#{actorNo}))>0 AND #{updatedAt} IS NOT NULL
                """)
        int refreshSourceSnapshot(@Param("entityNo") String entityNo, @Param("guardNo") String guardNo,
                @Param("expectedVersion") long expectedVersion,
                @Param("expectedSourceRevision") long expectedSourceRevision,
                @Param("newSourceRevision") long newSourceRevision, @Param("newSourceQty") BigDecimal newSourceQty,
                @Param("sourceHash") String sourceHash, @Param("actorNo") String actorNo,
                @Param("updatedAt") LocalDateTime updatedAt);

    }


    /** Persistent server previews; bytes must not be accepted from a public binding. */
    public interface AppointmentPreparationMapper extends BaseMapper<BtyAppointmentPreparation> { }
    public interface AppointmentArrangementMapper extends BaseMapper<BtyAppointmentArrangement> { }
    public interface AppointmentCurrentMapper extends BaseMapper<BtyAppointmentCurrent> {
        @Select("""
                SELECT c.* FROM bty_appointment_current c JOIN bty_appointment a
                ON a.tenant_no=c.tenant_no AND a.entity_no=c.entity_no AND a.appointment_no=c.appointment_no
                WHERE c.tenant_no='MAIN' AND c.entity_no=#{entityNo} AND c.appointment_no=#{appointmentNo}
                AND c.deleted=0 AND a.deleted=0 AND a.store_no=#{storeNo} FOR UPDATE
                """)
        BtyAppointmentCurrent selectScopedForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo,@Param("appointmentNo") String appointmentNo);
 }
    /** Durable operation claim; CAS leases are independent of actor authority and never authorize commands. */
    public interface AppointmentOperationMapper extends BaseMapper<BtyAppointmentOperation> {
        @Update("""
                UPDATE bty_appointment_operation SET lease_until=#{until},version=version+1
                WHERE tenant_no='MAIN' AND entity_no=#{entity} AND operation_no=#{operation}
                  AND version=#{version} AND state='PENDING' AND deleted=0
                  AND (lease_until IS NULL OR lease_until<#{now})
                """)
        int claim(@Param("entity") String entity, @Param("operation") String operation,
                @Param("version") long version, @Param("now") LocalDateTime now,
                @Param("until") LocalDateTime until);
    }
    public interface AppointmentResourceDecisionMapper extends BaseMapper<BtyAppointmentResourceDecision> { }
    /** Scoped reception persistence; authorization remains in services. */
    public interface ReceptionOperationMapper extends BaseMapper<BtyReceptionOperation> {
        @Select("""
                SELECT * FROM bty_reception_operation WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND store_no=#{storeNo} AND operation_no=#{operationNo} AND deleted=0
                """)
        BtyReceptionOperation selectScoped(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("operationNo") String operationNo);
        /** Eligibility is filtered before LIMIT; Java still verifies the authoritative binding and intent. */
        @Select("""
                SELECT r.* FROM bty_reception_operation r
                JOIN bty_appointment_operation a ON a.tenant_no=r.tenant_no AND a.entity_no=r.entity_no
                  AND a.store_no=r.store_no AND a.appointment_no=r.appointment_no
                  AND a.operation_no=r.appointment_operation_no AND a.deleted=0 AND a.action='CREATE'
                JOIN bty_appointment_preparation p ON p.tenant_no=a.tenant_no AND p.entity_no=a.entity_no
                  AND p.store_no=a.store_no AND p.preview_no=a.preview_no AND p.deleted=0
                JOIN bty_appointment root ON root.tenant_no=r.tenant_no AND root.entity_no=r.entity_no
                  AND root.store_no=r.store_no AND root.appointment_no=r.appointment_no AND root.deleted=0
                JOIN bty_appointment_resource_intent i ON i.tenant_no=a.tenant_no AND i.entity_no=a.entity_no
                  AND i.store_no=a.store_no AND i.appointment_no=a.appointment_no AND i.deleted=0
                  AND i.command_no=JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.commandNo'))
                  AND i.command_hash=JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.commandHash'))
                  AND i.plan_hash=JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.planHash'))
                  AND i.action=JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.action'))
                  AND i.source_revision=JSON_EXTRACT(a.receipt,'$.binding.sourceRevision')
                  AND i.frozen_plan=p.frozen_plan
                WHERE r.tenant_no='MAIN' AND r.deleted=0 AND r.action='RECEIVE_WALK_IN' AND r.endpoint='BAPI-048'
                  AND r.phase='RESOURCE_PENDING' AND r.next_retry_at<=#{now}
                  AND (r.lease_until IS NULL OR r.lease_until<#{now})
                  AND (r.failure_code IS NULL OR r.failure_code NOT IN
                    ('RESOURCE_RELEASE_REQUIRED','ARRIVAL_REQUIRES_RESCHEDULE'))
                  AND a.state IN ('PENDING','SUCCEEDED','REJECTED') AND a.receipt IS NOT NULL
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.status')) IN ('ACCEPTED','SUCCEEDED','REJECTED')
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.action')) IN ('RESERVE','CONFIRM')
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.action'))=
                    JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.action'))
                  AND JSON_EXTRACT(a.receipt,'$.binding.sourceRevision')=
                    JSON_EXTRACT(a.accept_payload,'$.sourceRevision')
                  AND JSON_TYPE(JSON_EXTRACT(a.receipt,'$.binding.sourceRevision'))='INTEGER'
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.planHash'))=p.plan_hash
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.frozenPayload'))=p.frozen_plan
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.frozenPayload'))=p.frozen_plan
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.tenantNo'))=r.tenant_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.scope.tenantNo'))=r.tenant_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.entityNo'))=r.entity_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.scope.entityNo'))=r.entity_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.storeNo'))=r.store_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.scope.storeNo'))=r.store_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.sourceNo'))=r.appointment_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.sourceNo'))=r.appointment_no
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.sourceType'))='BEAUTY_APPOINTMENT'
                  AND JSON_UNQUOTE(JSON_EXTRACT(a.accept_payload,'$.sourceType'))='BEAUTY_APPOINTMENT'
                  AND JSON_TYPE(JSON_EXTRACT(a.receipt,'$.binding.commandNo'))='STRING'
                  AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.commandNo')))>0
                  AND JSON_TYPE(JSON_EXTRACT(a.receipt,'$.binding.commandHash'))='STRING'
                  AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.commandHash')))>0
                  AND JSON_TYPE(JSON_EXTRACT(a.receipt,'$.binding.planHash'))='STRING'
                  AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.planHash')))>0
                  AND (a.state<>'REJECTED' OR (root.hold_no IS NULL
                    AND JSON_UNQUOTE(JSON_EXTRACT(a.receipt,'$.binding.action'))='RESERVE'))
                ORDER BY r.next_retry_at ASC,r.id ASC LIMIT 50
                """)
        List<BtyReceptionOperation> selectResourceRecoverable(@Param("now") LocalDateTime now);
        @Update("""
                UPDATE bty_reception_operation SET lease_until=#{until},version=version+1
                WHERE tenant_no='MAIN' AND entity_no=#{entityNo} AND store_no=#{storeNo}
                  AND operation_no=#{operationNo} AND deleted=0 AND version=#{version}
                  AND action='RECEIVE_WALK_IN' AND endpoint='BAPI-048' AND phase='RESOURCE_PENDING'
                  AND next_retry_at<=#{now} AND (lease_until IS NULL OR lease_until<#{now})
                """)
        int claimResource(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("operationNo") String operationNo, @Param("version") long version,
                @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);
        /** A successful opening is read only through its exact original context and live order parent. */
        @Select("""
                SELECT o.* FROM bty_reception_operation o JOIN bty_work_order_context c
                  ON c.tenant_no=o.tenant_no AND c.entity_no=o.entity_no AND c.store_no=o.store_no
                  AND c.source_reception_operation_no=o.operation_no AND c.work_order_no=o.work_order_no
                  AND c.identity_kind=o.identity_kind AND c.identity_ref=o.identity_ref AND c.deleted=0
                JOIN bty_work_order w ON w.tenant_no=c.tenant_no AND w.entity_no=c.entity_no
                  AND w.store_no=c.store_no AND w.work_order_no=c.work_order_no AND w.deleted=0
                  AND w.customer_ref=CONCAT(c.identity_kind,':',c.identity_ref)
                WHERE o.tenant_no='MAIN' AND o.entity_no=#{entityNo} AND o.store_no=#{storeNo}
                  AND o.work_order_no=#{workOrderNo} AND o.operation_no=#{operationNo} AND o.deleted=0
                  AND ((o.action='OPEN_WORK_ORDER' AND o.endpoint IN ('BAPI-052','BAPI-054'))
                    OR (o.action='RECEIVE_WALK_IN' AND o.endpoint='BAPI-048')) AND o.phase='SUCCEEDED'
                """)
        BtyReceptionOperation selectOpenedForWorkOrder(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("workOrderNo") String workOrderNo,
                @Param("operationNo") String operationNo);

        @Select("""
                SELECT * FROM bty_reception_operation WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                AND store_no=#{storeNo} AND operation_no=#{operationNo} AND deleted=0 FOR UPDATE
                """)
        BtyReceptionOperation selectScopedForUpdate(@Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo, @Param("operationNo") String operationNo);
    }

    /** Scoped reception persistence; authorization remains in services. */
    public interface WorkOrderContextMapper extends BaseMapper<BtyWorkOrderContext> {
        @Select("""
                SELECT * FROM bty_work_order_context WHERE tenant_no='MAIN' AND entity_no=#{entityNo}
                  AND store_no=#{storeNo} AND work_order_no=#{workOrderNo} AND deleted=0
                """)
        BtyWorkOrderContext selectScoped(@Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("workOrderNo") String workOrderNo);

    }

    /** Scoped reception persistence; authorization remains in services. */
    public interface GuestClaimReceiptMapper extends BaseMapper<BtyGuestClaimReceipt> {
    }

    /** Scoped reception persistence; authorization remains in services. */
    public interface ArrivalChallengeMapper extends BaseMapper<BtyArrivalChallenge> {
    }
}
