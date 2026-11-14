package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.QuantityFacts;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdServiceExecution;
import ai.neargo.shop.trade.entity.OrdServiceExecutionAction;
import ai.neargo.shop.trade.entity.OrdServiceExecutionUnit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The A3 owner must first lock the real parent/children/source roots in canonical order.
 * Explicit tenant/entity/store predicates permit its narrowly scoped executeWithoutScope calls;
 * these SQL methods neither authorize actors nor create transactions. Every mutation must affect exactly one row.
 */
@Mapper
public interface ServiceExecutionMapper extends BaseMapper<OrdServiceExecution> {
    /** Source line absence is serialized by the already locked actual parent, never by a guessed unit. */
    @Select("""
            SELECT * FROM ord_checkout_line
            WHERE tenant_no='MAIN' AND caller='BEAUTY' AND source_line_no=#{binding.sourceLineNo}
              AND operation_no=#{binding.sourceOperationNo} AND order_no=#{binding.orderNo}
              AND sub_order_no=#{binding.subOrderNo} AND deleted=0
              AND EXISTS (SELECT 1 FROM ord_checkout_operation o
                  WHERE o.tenant_no='MAIN' AND o.caller='BEAUTY' AND o.operation_no=#{binding.sourceOperationNo}
                    AND o.entity_no=#{binding.scope.entityNo} AND o.store_no=#{binding.scope.storeNo}
                    AND o.order_no=#{binding.orderNo} AND o.sub_order_no=#{binding.subOrderNo} AND o.deleted=0)
            FOR UPDATE
            """)
    OrdCheckoutLine lockLine(@Param("binding") ExecutionBinding binding);

    /** Original execution lookup cannot cross entity/store scope, including machine reads. */
    @Select("""
            SELECT * FROM ord_service_execution WHERE tenant_no='MAIN'
              AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND execution_no=#{executionNo}
            """)
    OrdServiceExecution find(@Param("scope") ExecutionScope scope, @Param("executionNo") String executionNo);

    /** Attempts follow permanent units in the canonical lock order. */
    @Select("""
            SELECT * FROM ord_service_execution WHERE tenant_no='MAIN'
              AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND execution_no=#{executionNo}
            FOR UPDATE
            """)
    OrdServiceExecution lockExecution(@Param("scope") ExecutionScope scope, @Param("executionNo") String executionNo);

    /** All original attempts are locked in execution number order after the source's units. */
    @Select("""
            SELECT e.* FROM ord_service_execution e JOIN ord_service_execution_unit u
              ON u.tenant_no=e.tenant_no AND u.unit_no=e.unit_no
              AND u.entity_no=e.entity_no AND u.store_no=e.store_no
            WHERE e.tenant_no='MAIN' AND e.entity_no=#{scope.entityNo} AND e.store_no=#{scope.storeNo}
              AND u.caller='BEAUTY' AND u.source_line_no=#{sourceLineNo}
            ORDER BY e.execution_no FOR UPDATE
            """)
    List<OrdServiceExecution> lockSourceExecutions(
            @Param("scope") ExecutionScope scope, @Param("sourceLineNo") String sourceLineNo);

    /** Key replay returns the original row, whose full payload/action hashes the owner must compare. */
    @Select("""
            SELECT * FROM ord_service_execution WHERE tenant_no='MAIN'
              AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND prepare_key_hash=#{keyHash}
            """)
    OrdServiceExecution findKey(@Param("scope") ExecutionScope scope, @Param("keyHash") String keyHash);

    /** Expected affected rows is one; M3 only creates original generation one in PREPARED. */
    @Insert("""
            INSERT INTO ord_service_execution (
                tenant_no, created_at, created_by, updated_at,
                updated_by, version, deleted, execution_no,
                unit_no, entity_no, store_no, attempt_no,
                grant_no, quantity, uom, state,
                binding_json, binding_hash, source_intent_hash, prepare_action_no,
                prepare_key_hash, prepare_action_hash, declaration_no, admission_revision,
                manual_source_revision, manual_source_hash, original_stock_manifest_hash, confirmation_no,
                stock_observation_hash, source_fence_no, source_fence_revision, resource_command_no,
                resource_command_hash, resource_outcome_no, resource_outcome_hash, observation_epoch,
                next_retry_at, reason_code
            ) SELECT
                #{row.tenantNo}, #{row.createdAt}, #{row.createdBy},
                #{row.updatedAt}, #{row.updatedBy}, #{row.version},
                #{row.deleted}, #{row.executionNo}, #{row.unitNo},
                #{row.entityNo}, #{row.storeNo}, #{row.attemptNo},
                #{row.grantNo}, #{row.quantity}, #{row.uom},
                #{row.state}, #{row.bindingJson}, #{row.bindingHash},
                #{row.sourceIntentHash}, #{row.prepareActionNo}, #{row.prepareKeyHash},
                #{row.prepareActionHash}, #{row.declarationNo}, #{row.admissionRevision},
                #{row.manualSourceRevision}, #{row.manualSourceHash}, #{row.originalStockManifestHash},
                #{row.confirmationNo}, #{row.stockObservationHash}, #{row.sourceFenceNo},
                #{row.sourceFenceRevision}, #{row.resourceCommandNo}, #{row.resourceCommandHash},
                #{row.resourceOutcomeNo}, #{row.resourceOutcomeHash}, #{row.observationEpoch},
                #{row.nextRetryAt}, #{row.reasonCode}
            WHERE #{row.state}='PREPARED' AND #{row.attemptNo}=1 AND #{row.quantity}=1 AND #{row.uom}='COUNT'
              AND #{row.resourceOutcomeNo} IS NULL AND #{row.resourceOutcomeHash} IS NULL
              AND EXISTS (SELECT 1 FROM ord_service_execution_unit u WHERE u.tenant_no=#{row.tenantNo}
                  AND u.unit_no=#{row.unitNo} AND u.entity_no=#{row.entityNo} AND u.store_no=#{row.storeNo}
                  AND u.state='RESERVED' AND u.current_execution_no=#{row.executionNo} AND u.attempt_no=1)
            """)
    int insertPrepared(@Param("row") OrdServiceExecution row);

    /** Suspension keeps the same reserved unit and never converts uncertainty into release. */
    @Update("""
            UPDATE ord_service_execution SET
              state=CASE WHEN state='PREPARED' THEN 'SUSPEND_REQUESTED' ELSE 'STARTED_SUSPENDED' END,
              reason_code=#{reason}, next_retry_at=#{retryAt}, updated_at=CURRENT_TIMESTAMP(3), version=version+1
            WHERE tenant_no='MAIN' AND entity_no=#{binding.scope.entityNo} AND store_no=#{binding.scope.storeNo}
              AND execution_no=#{binding.executionNo} AND binding_hash=#{bindingHash}
              AND source_fence_no=#{binding.sourceFenceNo} AND source_fence_revision=#{binding.sourceFenceRevision}
              AND version=#{expectedVersion} AND state IN ('PREPARED','STARTED')
            """)
    int requestSuspend(@Param("binding") ExecutionBinding binding, @Param("bindingHash") String bindingHash,
            @Param("expectedVersion") long expectedVersion, @Param("reason") String reason,
            @Param("retryAt") LocalDateTime retryAt);

    /** Claim advances a persisted epoch/CAS before owner IO so late workers cannot overwrite newer observations. */
    @Update("""
            UPDATE ord_service_execution SET observation_epoch=observation_epoch+1, next_retry_at=#{leaseUntil},
              reason_code=CASE WHEN state IN ('SUSPEND_REQUESTED','STARTED_SUSPENDED')
                THEN reason_code ELSE 'SOURCE_UNKNOWN' END, updated_at=CURRENT_TIMESTAMP(3), version=version+1
            WHERE tenant_no='MAIN' AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
              AND execution_no=#{executionNo} AND version=#{expectedVersion} AND observation_epoch=#{expectedEpoch}
              AND state IN ('PREPARED','SUSPEND_REQUESTED','STARTED','STARTED_SUSPENDED','RELEASING')
              AND (next_retry_at IS NULL OR next_retry_at<=#{now})
            """)
    int claimObservation(@Param("scope") ExecutionScope scope, @Param("executionNo") String executionNo,
            @Param("expectedVersion") long expectedVersion, @Param("expectedEpoch") long expectedEpoch,
            @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Complete original action identity and claimed epoch are mandatory; explicit SQL clears nullable retry fields. */
    @Update("""
            UPDATE ord_service_execution e SET reason_code=#{reason}, next_retry_at=#{retryAt},
              updated_at=CURRENT_TIMESTAMP(3), version=version+1
            WHERE e.tenant_no='MAIN' AND e.entity_no=#{scope.entityNo} AND e.store_no=#{scope.storeNo}
              AND e.execution_no=#{executionNo} AND e.binding_hash=#{bindingHash}
              AND e.version=#{expectedVersion} AND e.observation_epoch=#{expectedEpoch}
              AND e.state IN ('PREPARED','SUSPEND_REQUESTED','STARTED','STARTED_SUSPENDED','RELEASING')
              AND EXISTS (SELECT 1 FROM ord_service_execution_action a
                  WHERE a.tenant_no=e.tenant_no AND a.entity_no=e.entity_no AND a.store_no=e.store_no
                    AND a.execution_no=e.execution_no AND a.action_no=#{actionNo} AND a.action_hash=#{actionHash}
                    AND a.kind='OBSERVE' AND a.observation_epoch=e.observation_epoch
                    AND a.expected_revision=#{expectedVersion} AND a.result_revision IS NULL)
            """)
    int applyObservation(@Param("scope") ExecutionScope scope, @Param("executionNo") String executionNo,
            @Param("bindingHash") String bindingHash, @Param("expectedVersion") long expectedVersion,
            @Param("expectedEpoch") long expectedEpoch, @Param("actionNo") String actionNo,
            @Param("actionHash") String actionHash, @Param("reason") String reason,
            @Param("retryAt") LocalDateTime retryAt);

    /** A bounded due-time/id cursor permits recovery without assuming that missing tables mean success. */
    @Select("""
            SELECT * FROM ord_service_execution
            WHERE tenant_no='MAIN' AND #{limit} BETWEEN 1 AND 100 AND state IN
              ('PREPARED','SUSPEND_REQUESTED','STARTED','STARTED_SUSPENDED','RELEASING')
              AND (next_retry_at IS NULL OR next_retry_at<=#{now})
              AND (#{afterId}=0 OR
                (#{afterRetryAt} IS NULL AND (next_retry_at IS NOT NULL OR id>#{afterId})) OR
                (#{afterRetryAt} IS NOT NULL AND (next_retry_at>#{afterRetryAt} OR
                  (next_retry_at=#{afterRetryAt} AND id>#{afterId}))))
            ORDER BY next_retry_at,id LIMIT #{limit}
            """)
    List<OrdServiceExecution> pending(@Param("now") LocalDateTime now,
            @Param("afterRetryAt") LocalDateTime afterRetryAt, @Param("afterId") long afterId,
            @Param("limit") int limit);

    /** Unit state, not attempt count, defines the disjoint quantity buckets; zero rows returns no facts. */
    @Mapper
    interface UnitMapper extends BaseMapper<OrdServiceExecutionUnit> {
        /** Permanent association reads do not hide deleted identities. */
        @Select("""
                SELECT * FROM ord_service_execution_unit WHERE tenant_no='MAIN'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND unit_no=#{unitNo}
                """)
        OrdServiceExecutionUnit findUnit(@Param("scope") ExecutionScope scope, @Param("unitNo") String unitNo);

        /** Terminal/deleted filters are deliberately absent: original ownership is permanent. */
        @Select("""
                SELECT * FROM ord_service_execution_unit WHERE tenant_no='MAIN' AND caller='BEAUTY'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND source_line_no=#{sourceLineNo}
                ORDER BY unit_ordinal,task_no FOR UPDATE
                """)
        List<OrdServiceExecutionUnit> lockLineUnits(
                @Param("scope") ExecutionScope scope, @Param("sourceLineNo") String sourceLineNo);

        /** A same-task lookup includes terminal ownership so a new key cannot recycle the unit. */
        @Select("""
                SELECT * FROM ord_service_execution_unit WHERE tenant_no='MAIN' AND caller='BEAUTY'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND task_no=#{taskNo}
                """)
        OrdServiceExecutionUnit findTask(@Param("scope") ExecutionScope scope, @Param("taskNo") String taskNo);

        /** Only A3's already locked validated source may append a bound permanent identity. */
        @Insert("""
                INSERT INTO ord_service_execution_unit (
                    tenant_no, created_at, created_by, updated_at,
                    updated_by, version, deleted, unit_no,
                    entity_no, store_no, caller, source_operation_no,
                    order_no, sub_order_no, source_line_no, order_item_id,
                    unit_ordinal, ordered_units, quantity, uom,
                    task_no, work_order_no, binding_no, binding_revision,
                    state, current_execution_no, attempt_no, source_identity_hash
                ) SELECT
                    #{row.tenantNo}, #{row.createdAt}, #{row.createdBy},
                    #{row.updatedAt}, #{row.updatedBy}, #{row.version},
                    #{row.deleted}, #{row.unitNo}, #{row.entityNo},
                    #{row.storeNo}, #{row.caller}, #{row.sourceOperationNo},
                    #{row.orderNo}, #{row.subOrderNo}, #{row.sourceLineNo},
                    #{row.orderItemId}, #{row.unitOrdinal}, #{row.orderedUnits},
                    #{row.quantity}, #{row.uom}, #{row.taskNo},
                    #{row.workOrderNo}, #{row.bindingNo}, #{row.bindingRevision},
                    #{row.state}, #{row.currentExecutionNo}, #{row.attemptNo},
                    #{row.sourceIdentityHash}
                WHERE #{row.state}='BOUND' AND #{row.attemptNo}=0 AND #{row.currentExecutionNo} IS NULL
                  AND #{row.quantity}=1 AND #{row.uom}='COUNT'
                """)
        int insertBound(@Param("row") OrdServiceExecutionUnit row);

        /** First reservation is exactly generation one, with unchanged original task/binding/source coordinates. */
        @Update("""
                UPDATE ord_service_execution_unit SET state='RESERVED', current_execution_no=#{binding.executionNo},
                  attempt_no=1, updated_at=CURRENT_TIMESTAMP(3), version=version+1
                WHERE tenant_no='MAIN' AND caller='BEAUTY' AND entity_no=#{binding.scope.entityNo}
                  AND store_no=#{binding.scope.storeNo} AND source_line_no=#{binding.sourceLineNo}
                  AND source_operation_no=#{binding.sourceOperationNo} AND order_no=#{binding.orderNo}
                  AND sub_order_no=#{binding.subOrderNo} AND unit_ordinal=#{binding.unitOrdinal}
                  AND work_order_no=#{binding.workOrderNo} AND task_no=#{binding.taskNo}
                  AND binding_no=#{binding.bindingNo} AND binding_revision=#{binding.bindingRevision}
                  AND state='BOUND' AND current_execution_no IS NULL AND attempt_no=0 AND #{binding.attemptNo}=1
                  AND version=#{expectedVersion}
                """)
        int reserve(@Param("binding") ExecutionBinding binding, @Param("expectedVersion") long expectedVersion);

        /** No missing-source aggregate is coerced into an available capacity row. */
        @Select("""
                SELECT MAX(ordered_units) AS orderedUnits,
                  SUM(CASE WHEN state='RESERVED' THEN 1 ELSE 0 END) AS reservedUnits,
                  SUM(CASE WHEN state='PERFORMED' THEN 1 ELSE 0 END) AS performedUnits,
                  SUM(CASE WHEN state='VOIDED' THEN 1 ELSE 0 END) AS voidedUnits
                FROM ord_service_execution_unit WHERE tenant_no='MAIN' AND caller='BEAUTY'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND source_line_no=#{sourceLineNo}
                GROUP BY source_line_no HAVING MIN(ordered_units)=MAX(ordered_units)
                """)
        QuantityFacts quantity(@Param("scope") ExecutionScope scope, @Param("sourceLineNo") String sourceLineNo);

        // M4 finishRelease and M6 markPerformed/voidUnserved are staged with their real proof owners.
    }

    /** Action association is append-only; only its still-empty result tuple may be finalized once. */
    @Mapper
    interface ActionMapper extends BaseMapper<OrdServiceExecutionAction> {
        /** Compare prior committed source causes without replacing the immutable original fence revision. */
        @Select("""
                SELECT * FROM ord_service_execution_action WHERE tenant_no='MAIN'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND execution_no=#{executionNo} AND kind='SOURCE_SUSPEND'
                ORDER BY id DESC LIMIT 1
                """)
        OrdServiceExecutionAction latestSuspension(@Param("scope") ExecutionScope scope,
                @Param("executionNo") String executionNo);

        /** Exact original action identity protects both key replay and changed-hash conflict handling. */
        @Select("""
                SELECT * FROM ord_service_execution_action WHERE tenant_no='MAIN'
                  AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND execution_no=#{executionNo}
                  AND kind=#{kind} AND request_key_hash=#{keyHash}
                """)
        OrdServiceExecutionAction findKey(@Param("scope") ExecutionScope scope,
                @Param("executionNo") String executionNo, @Param("kind") String kind,
                @Param("keyHash") String keyHash);

        /** A source-suspension action stores its complete original proof before any result can be acknowledged. */
        @Insert("""
                INSERT INTO ord_service_execution_action (
                    tenant_no, created_at, created_by, updated_at,
                    updated_by, version, deleted, action_no,
                    execution_no, entity_no, store_no, kind,
                    actor_namespace_hash, request_key_hash, action_hash, expected_revision,
                    observation_epoch, result_state, result_revision, proof_json,
                    proof_hash
                ) SELECT
                    #{row.tenantNo}, #{row.createdAt}, #{row.createdBy},
                    #{row.updatedAt}, #{row.updatedBy}, #{row.version},
                    #{row.deleted}, #{row.actionNo}, #{row.executionNo},
                    #{row.entityNo}, #{row.storeNo}, #{row.kind},
                    #{row.actorNamespaceHash}, #{row.requestKeyHash}, #{row.actionHash},
                    #{row.expectedRevision}, #{row.observationEpoch}, #{row.resultState},
                    #{row.resultRevision}, #{row.proofJson}, #{row.proofHash}
                WHERE #{row.resultState} IS NULL AND #{row.resultRevision} IS NULL
                """)
        int insertPending(@Param("row") OrdServiceExecutionAction row);

        /** Expected affected rows is one; stale/changed/already finalized actions cannot be overwritten. */
        @Update("""
                UPDATE ord_service_execution_action SET result_state=#{state}, result_revision=#{resultRevision},
                  updated_at=CURRENT_TIMESTAMP(3), version=version+1
                WHERE tenant_no='MAIN' AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND action_no=#{actionNo} AND execution_no=#{executionNo} AND action_hash=#{actionHash}
                  AND version=#{expectedVersion} AND observation_epoch=#{expectedEpoch}
                  AND result_state IS NULL AND result_revision IS NULL
                """)
        int finalizeResult(@Param("scope") ExecutionScope scope, @Param("executionNo") String executionNo,
                @Param("actionNo") String actionNo, @Param("actionHash") String actionHash,
                @Param("expectedVersion") long expectedVersion, @Param("expectedEpoch") long expectedEpoch,
                @Param("state") String state, @Param("resultRevision") long resultRevision);
    }
}
