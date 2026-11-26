package ai.neargo.shop.merchant.booking.mapper;

import ai.neargo.shop.merchant.dto.PersonnelQualificationFactRow;
import ai.neargo.shop.merchant.dto.ResourceBookingAllocationRow;
import ai.neargo.shop.merchant.dto.ResourceBookingCounterRow;
import ai.neargo.shop.merchant.dto.ResourceCommandFactRow;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.dto.ResourceFactRow;
import ai.neargo.shop.merchant.dto.ScheduleRuleFactRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import ai.neargo.shop.merchant.booking.entity.MchAppointmentSlot;
import ai.neargo.shop.merchant.booking.entity.MchBookingHold;
import ai.neargo.shop.merchant.booking.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.booking.entity.MchBookingOwner;
import ai.neargo.shop.merchant.booking.entity.MchPersonnelSkill;
import ai.neargo.shop.merchant.booking.entity.MchResource;
import ai.neargo.shop.merchant.booking.entity.MchResourceBucket;
import ai.neargo.shop.merchant.booking.entity.MchResourceCommand;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupation;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationBucket;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationSegment;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationUnit;
import ai.neargo.shop.merchant.booking.entity.MchScheduleChangeLog;
import ai.neargo.shop.merchant.booking.entity.MchScheduleRule;

/**
 * 预约资源：资源、时段容量、预约占用、班表、服务资格、预约时段。
 *
 * <p>新的库表设计把这些放在门店交易库（{@code rsv_*}），和订单同库，才能在下单时同事务占用。
 * 从商户域的 Mapper 集合里拆出来；包外只有人员服务直接用（见 MerchantSubdomainBoundaryTest 的例外登记）。
 */
public final class BookingMappers {

    private BookingMappers() {
    }

    /** 稳定资源仲裁归属数据访问；完整仲裁由后续服务编排。 */
    public interface BookingOwnerMapper extends BaseMapper<MchBookingOwner> {
        /**
         * Shared parent guards use explicit trusted scope. JSQLParser cannot parse LOCK IN SHARE MODE, so only
         * this fully scoped statement skips data-permission rewriting; generic mapper interception stays enabled.
         */
        @InterceptorIgnore(dataPermission = "true")
        @Select("""
                SELECT e.tenant_no,e.entity_no,s.store_no FROM mch_entity e
                JOIN mch_store s ON s.tenant_no=e.tenant_no AND s.entity_no=e.entity_no
                WHERE e.tenant_no=#{tenantNo} AND e.entity_no=#{entityNo} AND s.store_no=#{storeNo}
                  AND e.status='ACTIVE' AND e.archived_at IS NULL AND e.deleted=0
                  AND s.status='ACTIVE' AND s.deleted=0 LOCK IN SHARE MODE
                """)
        ResourceCommandScopeRow lockScope(@Param("tenantNo") String tenantNo, @Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo);

        /** Advisory normalization uses actual owner collation before reading binary actual evidence. */
        @Select("""
                SELECT e.tenant_no,e.entity_no,s.store_no FROM mch_entity e
                JOIN mch_store s ON s.tenant_no=e.tenant_no AND s.entity_no=e.entity_no
                WHERE e.tenant_no=#{tenantNo} AND e.entity_no=#{entityNo} AND s.store_no=#{storeNo}
                  AND e.status='ACTIVE' AND e.archived_at IS NULL AND e.deleted=0
                  AND s.status='ACTIVE' AND s.deleted=0
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        ResourceCommandScopeRow selectScope(@Param("tenantNo") String tenantNo, @Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo);

        /** Current root read; a missing row is established by scoped unique insert, not an unlocked absence check. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND source_type=#{sourceType} AND source_no=#{sourceNo}
                  AND deleted=0 FOR UPDATE
                """)
        MchBookingOwner lockBySource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("sourceType") String sourceType, @Param("sourceNo") String sourceNo);

        /** Nonlocking replay read occurs only after the duplicate-insert transaction has rolled back. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND source_type=#{sourceType} AND source_no=#{sourceNo} AND deleted=0
                """)
        MchBookingOwner selectBySource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("sourceType") String sourceType, @Param("sourceNo") String sourceNo);

        /** Stable source binding for recovery receipts; no caller-supplied source identity is trusted on query. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND deleted=0
                """)
        MchBookingOwner selectOwner(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo);

        /** Must precede command/resource locks; every business command serializes on this stable root. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND deleted=0 FOR UPDATE
                """)
        MchBookingOwner lockOwner(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo);

        /** Root CAS is part of the same local transaction as capacity evidence and the terminal command result. */
        @Update("""
                UPDATE mch_booking_owner SET current_hold_no=#{holdNo},source_revision=#{sourceRevision},
                  owner_version=owner_version+1,version=version+1,updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND owner_no=#{ownerNo} AND owner_version=#{expectedVersion} AND deleted=0
                  AND owner_version<9223372036854775807 AND version<9223372036854775807
                  AND (current_hold_no=#{previousHoldNo} OR (current_hold_no IS NULL AND #{previousHoldNo} IS NULL))
                """)
        int advanceHead(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo,
                @Param("expectedVersion") long expectedVersion, @Param("previousHoldNo") String previousHoldNo,
                @Param("holdNo") String holdNo, @Param("sourceRevision") Long sourceRevision,
                @Param("actor") String actor, @Param("now") LocalDateTime now);

    }

    /** 资源持久命令与结果数据访问；完整仲裁由后续服务编排。 */
    public interface ResourceCommandMapper extends BaseMapper<MchResourceCommand> {
        /** One original parent-chain snapshot; never substitutes the owner's current hold or latest command. */
        @Select("""
                SELECT c.tenant_no,c.entity_no,c.store_no,c.command_no,o.source_type,o.source_no,
                  c.source_revision,c.action,c.payload_hash AS command_hash,c.frozen_payload,c.status,
                  c.version AS revision,c.result_payload,c.rejection_code,c.command_kind
                FROM mch_resource_command c JOIN mch_booking_owner o ON o.tenant_no=c.tenant_no
                  AND o.entity_no=c.entity_no AND o.store_no=c.store_no AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.command_no=#{commandNo} AND c.deleted=0
                """)
        ResourceCommandFactRow selectOriginalFact(@Param("scope") ResourceCommandScopeRow scope,
                @Param("commandNo") String commandNo);

        /** Explicit parent chain prevents recovery by command number from crossing scope or a foreign owner. */
        @Select("""
                SELECT c.* FROM mch_resource_command c JOIN mch_booking_owner o
                  ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.store_no=c.store_no
                  AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.command_no=#{commandNo} AND c.deleted=0
                """)
        MchResourceCommand selectCommand(@Param("scope") ResourceCommandScopeRow scope,
                @Param("commandNo") String commandNo);

        /** Immutable key evidence; no update is performed during a replay. */
        @Select("""
                SELECT c.* FROM mch_resource_command c JOIN mch_booking_owner o
                  ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.store_no=c.store_no
                  AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.action=#{action} AND c.request_key_hash=#{keyHash} AND c.deleted=0
                """)
        MchResourceCommand selectByKey(@Param("scope") ResourceCommandScopeRow scope, @Param("action") String action,
                @Param("keyHash") String keyHash);

        /** Caller has already locked the scoped owner; this current read cannot reuse an old RR snapshot. */
        @Select("""
                SELECT * FROM mch_resource_command WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND command_no=#{commandNo}
                  AND deleted=0 FOR UPDATE
                """)
        MchResourceCommand lockCommand(@Param("scope") ResourceCommandScopeRow scope,
                @Param("ownerNo") String ownerNo, @Param("commandNo") String commandNo);

        /** Explicit audit/version and ACCEPTED predicate protect terminal records from stale recovery workers. */
        @Update("""
                UPDATE mch_resource_command SET wait_reason=#{reason},next_retry_at=#{retryAt},version=version+1,
                  updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND command_no=#{commandNo} AND command_kind='BOOKING_V1' AND status='ACCEPTED' AND version=#{version} AND deleted=0
                  AND version<9223372036854775807
                """)
        int markWaiting(@Param("scope") ResourceCommandScopeRow scope, @Param("commandNo") String commandNo,
                @Param("version") long version, @Param("reason") String reason,
                @Param("retryAt") LocalDateTime retryAt, @Param("actor") String actor,
                @Param("now") LocalDateTime now);

        /** Frozen request columns are deliberately absent; clearing retry fields is part of terminalization. */
        @Update("""
                UPDATE mch_resource_command SET status=#{status},result_payload=#{result},rejection_code=#{code},
                  wait_reason=NULL,next_retry_at=NULL,version=version+1,updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND command_no=#{commandNo} AND command_kind='BOOKING_V1' AND status='ACCEPTED' AND version=#{version} AND deleted=0
                  AND version<9223372036854775807
                """)
        int finishAccepted(@Param("scope") ResourceCommandScopeRow scope, @Param("commandNo") String commandNo,
                @Param("version") long version, @Param("status") String status, @Param("result") String result,
                @Param("code") String code, @Param("actor") String actor, @Param("now") LocalDateTime now);

    }

    /** Native evidence queries are called only after actual supported-schema inspection. */
    public interface ResourceOccupationMapper extends BaseMapper<MchResourceOccupation> {
        /** Common contract is available on projected H2; no native table is referenced here. */
        @Select("""
                SELECT (SELECT COUNT(*) FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo}
                    AND entity_no=#{scope.entityNo} AND (occupation_association_state<>'UNBOUND'
                    OR planned_resource_command_no IS NOT NULL OR planned_resource_command_hash IS NOT NULL
                    OR execution_reference_json IS NOT NULL OR execution_reference_hash IS NOT NULL
                    OR execution_plan_hash IS NOT NULL OR initial_start_command_no IS NOT NULL OR seal_command_no IS NOT NULL))
                + (SELECT COUNT(*) FROM mch_resource_command WHERE tenant_no=#{scope.tenantNo}
                    AND entity_no=#{scope.entityNo} AND (command_kind<>'BOOKING_V1' OR payload_schema IS NOT NULL
                    OR execution_reference_json IS NOT NULL OR execution_reference_hash IS NOT NULL
                    OR planned_resource_command_no IS NOT NULL OR planned_resource_command_hash IS NOT NULL
                    OR execution_plan_hash IS NOT NULL OR original_start_command_no IS NOT NULL
                    OR actor_namespace IS NOT NULL OR actor_provenance_hash IS NOT NULL OR machine_purpose IS NOT NULL
                    OR original_result_no IS NOT NULL OR original_result_revision IS NOT NULL OR original_result_hash IS NOT NULL
                    OR occupation_outcome IS NOT NULL OR seal_no IS NOT NULL OR seal_revision IS NOT NULL
                    OR seal_hash IS NOT NULL OR observation_epoch<>0))
                + (SELECT COUNT(*) FROM mch_booking_hold_unit WHERE tenant_no=#{scope.tenantNo}
                    AND entity_no=#{scope.entityNo} AND (consume_occupation_unit_no IS NOT NULL
                    OR consumed_at IS NOT NULL OR consume_command_no IS NOT NULL))
                + (SELECT COUNT(*) FROM mch_resource_bucket WHERE tenant_no=#{scope.tenantNo}
                    AND entity_no=#{scope.entityNo} AND (occupied_qty<>0 OR cleaning_qty<>0))
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countCommonArtifacts(@Param("scope") ResourceCommandScopeRow scope);

        /** Validate permanent consumption in both directions, including deleted originals and missing parents. */
        @Select("""
                SELECT (SELECT COUNT(*) FROM mch_resource_occupation_unit x
                  LEFT JOIN mch_resource_occupation h ON h.tenant_no=x.tenant_no AND h.entity_no=x.entity_no
                    AND h.occupation_no=x.occupation_no
                  LEFT JOIN mch_booking_hold_unit u ON u.tenant_no=x.tenant_no AND u.entity_no=x.entity_no
                    AND u.unit_no=x.reservation_unit_no
                  LEFT JOIN mch_booking_hold bh ON bh.tenant_no=u.tenant_no AND bh.entity_no=u.entity_no
                    AND bh.hold_no=u.hold_no
                  WHERE x.tenant_no=#{scope.tenantNo} AND x.entity_no=#{scope.entityNo} AND
                    (x.deleted<>0 OR h.id IS NULL OR h.deleted<>0 OR NOT (x.start_command_no <=> h.start_command_no)
                    OR x.origin IS NULL OR x.origin NOT IN ('BOOKING','DIRECT')
                    OR (x.origin='DIRECT' AND (x.reservation_unit_no IS NOT NULL OR x.hold_no IS NOT NULL
                      OR x.booking_owner_no IS NOT NULL OR x.source_line_ref IS NOT NULL
                      OR x.allocation_revision IS NOT NULL OR x.source_bucket_no IS NOT NULL))
                    OR (x.origin='BOOKING' AND (x.reservation_unit_no IS NULL OR x.hold_no IS NULL
                      OR x.booking_owner_no IS NULL OR x.source_line_ref IS NULL OR x.allocation_revision IS NULL
                      OR x.source_bucket_no IS NULL OR x.allocation_revision<=0))
                    OR NOT EXISTS (SELECT 1 FROM mch_resource_occupation_segment segment
                      WHERE segment.tenant_no=x.tenant_no AND segment.entity_no=x.entity_no
                        AND segment.occupation_no=x.occupation_no AND segment.logical_demand_no=x.logical_demand_no
                        AND segment.deleted=0)
                    OR (x.origin='BOOKING' AND (u.id IS NULL OR u.deleted<>0 OR u.released_at IS NOT NULL
                    OR u.consume_occupation_unit_no IS NULL OR NOT (u.consume_occupation_unit_no <=> x.occupation_unit_no)
                    OR u.consumed_at IS NULL OR NOT (u.consumed_at <=> x.consumed_at)
                    OR u.consume_command_no IS NULL OR NOT (u.consume_command_no <=> x.start_command_no)
                    OR NOT (u.qty <=> x.qty) OR NOT (u.hold_no <=> x.hold_no) OR NOT (u.bucket_no <=> x.source_bucket_no)
                    OR NOT (u.allocation_revision <=> x.allocation_revision) OR NOT (u.source_line_ref <=> x.source_line_ref)
                    OR bh.id IS NULL OR bh.deleted<>0 OR NOT (bh.owner_no <=> x.booking_owner_no)))))
                + (SELECT COUNT(*) FROM mch_booking_hold_unit u LEFT JOIN mch_resource_occupation_unit x
                    ON x.tenant_no=u.tenant_no AND x.entity_no=u.entity_no
                    AND x.occupation_unit_no=u.consume_occupation_unit_no
                  WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo}
                    AND (u.consume_occupation_unit_no IS NOT NULL OR u.consumed_at IS NOT NULL OR u.consume_command_no IS NOT NULL)
                    AND (x.id IS NULL OR NOT (x.reservation_unit_no <=> u.unit_no) OR x.deleted<>0))
                + (SELECT COUNT(*) FROM mch_resource_command c
                  WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo}
                    AND c.command_kind='OCCUPATION_V1' AND c.action='START'
                    AND c.status='SUCCEEDED' AND c.occupation_outcome='APPLIED'
                    AND (c.deleted<>0 OR (SELECT COUNT(*) FROM mch_resource_occupation h
                      WHERE h.tenant_no=c.tenant_no AND h.entity_no=c.entity_no AND h.store_no=c.store_no
                        AND h.owner_no=c.owner_no AND h.start_command_no=c.command_no AND h.deleted=0
                        AND h.payload_hash=c.payload_hash AND h.reference_json=c.execution_reference_json
                        AND h.reference_hash=c.execution_reference_hash
                        AND h.planned_resource_command_no=c.planned_resource_command_no
                        AND h.planned_resource_command_hash=c.planned_resource_command_hash
                        AND h.plan_hash=c.execution_plan_hash AND h.result_no=c.original_result_no
                        AND h.result_revision=c.original_result_revision AND h.result_hash=c.original_result_hash)<>1))
                + (SELECT COUNT(*) FROM mch_resource_occupation h
                  LEFT JOIN mch_booking_owner o ON o.tenant_no=h.tenant_no AND o.entity_no=h.entity_no
                    AND o.store_no=h.store_no AND o.owner_no=h.owner_no
                  LEFT JOIN mch_resource_command c ON c.tenant_no=h.tenant_no AND c.entity_no=h.entity_no
                    AND c.store_no=h.store_no AND c.command_no=h.start_command_no AND c.owner_no=h.owner_no
                  WHERE h.tenant_no=#{scope.tenantNo} AND h.entity_no=#{scope.entityNo} AND
                    (h.deleted<>0 OR o.id IS NULL OR o.deleted<>0 OR NOT (o.source_type <=> 'SERVICE_EXECUTION')
                    OR NOT (o.source_no <=> h.execution_no) OR NOT (o.occupation_association_state <=> 'ACCEPTED')
                    OR NOT (o.initial_start_command_no <=> h.start_command_no)
                    OR NOT (o.planned_resource_command_no <=> h.planned_resource_command_no)
                    OR NOT (o.planned_resource_command_hash <=> h.planned_resource_command_hash)
                    OR NOT (o.execution_reference_hash <=> h.reference_hash) OR NOT (o.execution_plan_hash <=> h.plan_hash)
                    OR c.id IS NULL OR c.deleted<>0 OR NOT (c.command_kind <=> 'OCCUPATION_V1') OR NOT (c.action <=> 'START')
                    OR NOT (c.status <=> 'SUCCEEDED') OR NOT (c.occupation_outcome <=> 'APPLIED')
                    OR NOT (c.payload_hash <=> h.payload_hash) OR NOT (c.original_result_no <=> h.result_no)
                    OR NOT (c.original_result_hash <=> h.result_hash) OR NOT (c.original_result_revision <=> h.result_revision)
                    OR NOT (c.planned_resource_command_no <=> h.planned_resource_command_no)
                    OR NOT (c.planned_resource_command_hash <=> h.planned_resource_command_hash)
                    OR NOT (c.execution_reference_json <=> h.reference_json)
                    OR NOT (c.execution_reference_hash <=> h.reference_hash)
                    OR NOT (c.execution_plan_hash <=> h.plan_hash)
                    OR NOT (o.execution_reference_json <=> h.reference_json)
                    OR (h.state IN ('OPEN','PAUSED') AND NOT EXISTS
                      (SELECT 1 FROM mch_resource_occupation_segment s WHERE s.tenant_no=h.tenant_no
                      AND s.entity_no=h.entity_no AND s.occupation_no=h.occupation_no AND s.actual_end_at IS NULL))
                    OR NOT EXISTS (SELECT 1 FROM mch_resource_occupation_unit x WHERE x.tenant_no=h.tenant_no
                      AND x.entity_no=h.entity_no AND x.occupation_no=h.occupation_no)))
                + (SELECT COUNT(*) FROM mch_resource_occupation_segment s
                  LEFT JOIN mch_resource_occupation h ON h.tenant_no=s.tenant_no AND h.entity_no=s.entity_no
                    AND h.occupation_no=s.occupation_no
                  LEFT JOIN mch_resource r ON r.tenant_no=s.tenant_no AND r.entity_no=s.entity_no
                    AND r.resource_no=s.resource_no
                  WHERE s.tenant_no=#{scope.tenantNo} AND s.entity_no=#{scope.entityNo} AND
                    (s.deleted<>0 OR h.id IS NULL OR h.deleted<>0 OR r.id IS NULL OR r.deleted<>0
                    OR (s.actual_end_at IS NULL AND h.state NOT IN ('OPEN','PAUSED'))
                    OR SHA2(s.source_units_json,256)<>s.source_units_hash
                    OR JSON_LENGTH(s.source_units_json)<>(SELECT COUNT(*) FROM mch_resource_occupation_unit x
                      WHERE x.tenant_no=s.tenant_no AND x.entity_no=s.entity_no
                      AND x.occupation_no=s.occupation_no AND x.logical_demand_no=s.logical_demand_no)
                    OR EXISTS (SELECT 1 FROM mch_resource_occupation_unit x WHERE x.tenant_no=s.tenant_no
                      AND x.entity_no=s.entity_no AND x.occupation_no=s.occupation_no
                      AND x.logical_demand_no=s.logical_demand_no AND (x.qty<>s.qty
                      OR NOT JSON_CONTAINS(s.source_units_json, JSON_QUOTE(x.occupation_unit_no))))
                    OR (s.previous_segment_no IS NULL AND (NOT (s.command_no <=> h.start_command_no)
                      OR EXISTS (SELECT 1 FROM mch_resource_occupation_unit x
                        LEFT JOIN mch_resource_bucket origin_bucket ON origin_bucket.tenant_no=x.tenant_no
                          AND origin_bucket.entity_no=x.entity_no AND origin_bucket.bucket_no=x.source_bucket_no
                        WHERE x.tenant_no=s.tenant_no AND x.entity_no=s.entity_no
                          AND x.occupation_no=s.occupation_no AND x.logical_demand_no=s.logical_demand_no
                          AND x.origin='BOOKING' AND (origin_bucket.id IS NULL
                          OR NOT (origin_bucket.resource_no <=> s.resource_no)
                          OR NOT (x.source_line_ref <=> s.source_group_ref)))))
                    OR (s.previous_segment_no IS NOT NULL AND NOT EXISTS
                      (SELECT 1 FROM mch_resource_occupation_segment prior_segment
                        JOIN mch_resource_command c ON c.tenant_no=s.tenant_no AND c.entity_no=s.entity_no
                          AND c.command_no=s.command_no AND c.owner_no=h.owner_no
                        WHERE prior_segment.tenant_no=s.tenant_no AND prior_segment.entity_no=s.entity_no
                          AND prior_segment.occupation_no=s.occupation_no AND prior_segment.logical_demand_no=s.logical_demand_no
                          AND prior_segment.segment_no=s.previous_segment_no AND prior_segment.deleted=0
                          AND prior_segment.actual_end_at=s.actual_start_at AND c.command_kind='OCCUPATION_V1'
                          AND c.action='CHANGE' AND c.original_start_command_no=h.start_command_no
                          AND c.status='SUCCEEDED' AND c.deleted=0))
                    OR NOT EXISTS (SELECT 1 FROM mch_resource_occupation_unit x WHERE x.tenant_no=s.tenant_no
                      AND x.entity_no=s.entity_no AND x.occupation_no=s.occupation_no
                      AND x.logical_demand_no=s.logical_demand_no)))
                + (SELECT COUNT(*) FROM mch_resource_occupation_bucket p
                  LEFT JOIN mch_resource_occupation_segment s ON s.tenant_no=p.tenant_no AND s.entity_no=p.entity_no
                    AND s.occupation_no=p.occupation_no AND s.segment_no=p.segment_no
                    AND s.logical_demand_no=p.logical_demand_no AND s.resource_no=p.resource_no
                  LEFT JOIN mch_resource_bucket b ON b.tenant_no=p.tenant_no AND b.entity_no=p.entity_no
                    AND b.bucket_no=p.bucket_no AND b.resource_no=p.resource_no AND b.start_at=p.start_at
                  WHERE p.tenant_no=#{scope.tenantNo} AND p.entity_no=#{scope.entityNo} AND
                    (p.deleted<>0 OR s.id IS NULL OR s.deleted<>0 OR NOT (s.qty <=> p.qty)
                    OR b.id IS NULL OR b.deleted<>0 OR NOT (b.end_at <=> p.end_at)))
                + (SELECT COUNT(*) FROM mch_resource_bucket b WHERE b.tenant_no=#{scope.tenantNo}
                  AND b.entity_no=#{scope.entityNo} AND (b.occupied_qty<>(SELECT COALESCE(SUM(CAST(p.qty AS DECIMAL(20,0))),0)
                    FROM mch_resource_occupation_bucket p WHERE p.tenant_no=b.tenant_no AND p.entity_no=b.entity_no
                      AND p.bucket_no=b.bucket_no AND p.category='OCCUPIED' AND p.retired_at IS NULL AND p.deleted=0)
                  OR b.cleaning_qty<>(SELECT COALESCE(SUM(CAST(p.qty AS DECIMAL(20,0))),0)
                    FROM mch_resource_occupation_bucket p WHERE p.tenant_no=b.tenant_no AND p.entity_no=b.entity_no
                      AND p.bucket_no=b.bucket_no AND p.category='CLEANING' AND p.retired_at IS NULL AND p.deleted=0)))
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countInvalid(@Param("scope") ResourceCommandScopeRow scope);

        /** Exact projections already represented in the two actual counters. */
        @Select("""
                SELECT COALESCE(SUM(CAST(qty AS DECIMAL(20,0))),0) FROM mch_resource_occupation_bucket
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND resource_no=#{resource}
                  AND start_at=#{start} AND category=#{category} AND retired_at IS NULL AND deleted=0
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long projected(@Param("scope") ResourceCommandScopeRow scope, @Param("resource") String resource,
                @Param("start") LocalDateTime start, @Param("category") String category);

        /** One row per continuous logical demand; never multiply by its original multibucket booking units. */
        @Select("""
                SELECT COALESCE(SUM(CAST(s.qty AS DECIMAL(20,0))),0) FROM mch_resource_occupation_segment s
                JOIN mch_resource_occupation h ON h.tenant_no=s.tenant_no AND h.entity_no=s.entity_no
                  AND h.occupation_no=s.occupation_no AND h.deleted=0
                WHERE s.tenant_no=#{scope.tenantNo} AND s.entity_no=#{scope.entityNo}
                  AND s.resource_no=#{resource} AND s.deleted=0 AND
                  ((s.actual_end_at IS NULL AND h.state IN ('OPEN','PAUSED') AND s.actual_start_at<#{end}
                    AND NOT EXISTS (SELECT 1 FROM mch_resource_occupation_bucket p
                      WHERE p.tenant_no=s.tenant_no AND p.entity_no=s.entity_no AND p.occupation_no=s.occupation_no
                        AND p.segment_no=s.segment_no AND p.logical_demand_no=s.logical_demand_no
                        AND p.resource_no=s.resource_no AND p.start_at=#{start} AND p.end_at=#{end}
                        AND p.category='OCCUPIED' AND p.retired_at IS NULL AND p.deleted=0))
                  OR (s.cleaning_from IS NOT NULL AND s.cleaning_completed_at IS NULL AND s.cleaning_from<#{end}
                    AND NOT EXISTS (SELECT 1 FROM mch_resource_occupation_bucket p
                      WHERE p.tenant_no=s.tenant_no AND p.entity_no=s.entity_no AND p.occupation_no=s.occupation_no
                        AND p.segment_no=s.segment_no AND p.logical_demand_no=s.logical_demand_no
                        AND p.resource_no=s.resource_no AND p.start_at=#{start} AND p.end_at=#{end}
                        AND p.category='CLEANING' AND p.retired_at IS NULL AND p.deleted=0)))
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long missing(@Param("scope") ResourceCommandScopeRow scope, @Param("resource") String resource,
                @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

        /** No owner acquisition here: maintenance already holds canonical personnel/resource roots. */
        @Select("""
                SELECT COUNT(*) FROM mch_resource_occupation_segment s WHERE s.tenant_no=#{scope.tenantNo}
                  AND s.entity_no=#{scope.entityNo} AND s.resource_no=#{resource}
                  AND (s.actual_end_at IS NULL OR (s.cleaning_from IS NOT NULL AND s.cleaning_completed_at IS NULL)
                    OR EXISTS (SELECT 1 FROM mch_resource_occupation_bucket p WHERE p.tenant_no=s.tenant_no
                    AND p.entity_no=s.entity_no AND p.segment_no=s.segment_no AND p.retired_at IS NULL AND p.end_at>#{at}))
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countWork(@Param("scope") ResourceCommandScopeRow scope, @Param("resource") String resource,
                @Param("at") LocalDateTime at);
    }

    public interface ResourceOccupationUnitMapper extends BaseMapper<MchResourceOccupationUnit> { }

    public interface ResourceOccupationSegmentMapper extends BaseMapper<MchResourceOccupationSegment> { }

    public interface ResourceOccupationBucketMapper extends BaseMapper<MchResourceOccupationBucket> { }

    /** 跨店统一容量仲裁桶数据访问；完整仲裁由后续服务编排。 */
    public interface ResourceBucketMapper extends BaseMapper<MchResourceBucket> {
        /** Never infer availability from a drifted count or silently drop orphaned/deleted active allocations. */
        @Select("SELECT COALESCE(SUM(CASE WHEN h.status='HELD' AND u.consumed_at IS NULL THEN u.qty ELSE 0 END),0) AS held,"
                + "COALESCE(SUM(CASE WHEN h.status='CONFIRMED' AND u.consumed_at IS NULL THEN u.qty ELSE 0 END),0) AS confirmed,"
                + "COALESCE(SUM(CASE WHEN h.id IS NULL OR h.deleted<>0 OR u.deleted<>0 "
                + "OR h.status NOT IN ('HELD','CONFIRMED') OR o.id IS NULL OR o.deleted<>0 "
                + "OR o.current_hold_no IS NULL OR o.current_hold_no<>h.hold_no "
                + "OR o.source_revision<>h.source_revision THEN 1 ELSE 0 END),0) AS invalid "
                + "FROM mch_booking_hold_unit u LEFT JOIN mch_booking_hold h ON h.tenant_no=u.tenant_no "
                + "AND h.entity_no=u.entity_no AND h.hold_no=u.hold_no LEFT JOIN mch_booking_owner o "
                + "ON o.tenant_no=h.tenant_no AND o.entity_no=h.entity_no AND o.store_no=h.store_no "
                + "AND o.owner_no=h.owner_no WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo} "
                + "AND u.bucket_no=#{bucket} AND u.released_at IS NULL")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        ResourceBookingCounterRow selectBookingCounters(@Param("scope") ResourceCommandScopeRow scope,
                @Param("bucket") String bucket);

        /** Root locks are already held; bucket identity deliberately excludes store for shared STAFF capacity. */
        @Select("SELECT * FROM mch_resource_bucket WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND resource_no=#{resource} AND start_at=#{start} "
                + "AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResourceBucket lockBookingBucket(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resource") String resource, @Param("start") LocalDateTime start);

        /** Signed net deltas avoid replacement double charging; SQL independently enforces nonnegative capacity. */
        @Update("UPDATE mch_resource_bucket SET held_qty=held_qty+#{held},confirmed_qty=confirmed_qty+#{confirmed},"
                + "version=version+1,updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND bucket_no=#{bucket} AND version=#{version} AND deleted=0 "
                + "AND version<9223372036854775807 AND held_qty+#{held}>=0 AND confirmed_qty+#{confirmed}>=0 "
                + "AND CAST(held_qty AS DECIMAL(20,0))+confirmed_qty+occupied_qty+cleaning_qty+#{held}+#{confirmed}<=capacity")
        int adjustBookingCapacity(@Param("scope") ResourceCommandScopeRow scope, @Param("bucket") String bucket,
                @Param("version") long version, @Param("held") long held, @Param("confirmed") long confirmed,
                @Param("actor") String actor, @Param("at") LocalDateTime at);
    }

    /** 资源集合持有凭证数据访问；完整仲裁由后续服务编排。 */
    public interface BookingHoldMapper extends BaseMapper<MchBookingHold> {
        /** Acquired after the full personnel/resource union, never before discovering additional roots. */
        @Select("SELECT * FROM mch_booking_hold WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} "
                + "AND store_no=#{scope.storeNo} AND owner_no=#{owner} AND hold_no=#{hold} AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchBookingHold lockBookingHold(@Param("scope") ResourceCommandScopeRow scope, @Param("owner") String owner,
                @Param("hold") String hold);

        /** Explicit nullable termination/link writes and revision CAS preserve history and immutable creation hash. */
        @Update("UPDATE mch_booking_hold SET status=#{next.status},source_revision=#{next.sourceRevision},"
                + "lease_revision=#{next.leaseRevision},expires_at=#{next.expiresAt},"
                + "expiration_policy=#{next.expirationPolicy},replaced_by_hold_no=#{next.replacedByHoldNo},"
                + "termination_reason=#{next.terminationReason},terminated_at=#{next.terminatedAt},"
                + "version=version+1,updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND owner_no=#{next.ownerNo} "
                + "AND hold_no=#{next.holdNo} AND version=#{next.version} AND deleted=0 "
                + "AND version<9223372036854775807 AND execution_started_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM mch_booking_hold_unit u WHERE u.tenant_no=#{scope.tenantNo} "
                + "AND u.entity_no=#{scope.entityNo} AND u.hold_no=#{next.holdNo} "
                + "AND (u.consumed_at IS NOT NULL OR u.consume_occupation_unit_no IS NOT NULL OR u.consume_command_no IS NOT NULL))")
        int saveBookingHold(@Param("scope") ResourceCommandScopeRow scope, @Param("next") MchBookingHold next,
                @Param("actor") String actor, @Param("at") LocalDateTime at);

        /** Resolves canonical hold identity and owner/store parent chain before a command can reference it. */
        @Select("""
                SELECT * FROM mch_booking_hold WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND hold_no=#{holdNo} AND deleted=0
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchBookingHold selectOwnedHold(@Param("scope") ResourceCommandScopeRow scope,
                @Param("ownerNo") String ownerNo, @Param("holdNo") String holdNo);

        /** Consumption is permanent, including after actual service and cleaning have ended. */
        @Select("SELECT COUNT(*) FROM mch_booking_hold_unit WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND hold_no=#{holdNo} AND (consumed_at IS NOT NULL "
                + "OR consume_occupation_unit_no IS NOT NULL OR consume_command_no IS NOT NULL)")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countConsumed(@Param("scope") ResourceCommandScopeRow scope, @Param("holdNo") String holdNo);

        /** Old-head retirement includes soft-deleted unreleased units; deletion must never manufacture a release. */
        @Select("""
                SELECT COUNT(*) FROM mch_booking_hold_unit
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND hold_no=#{holdNo}
                  AND released_at IS NULL AND (#{includeDeleted}=TRUE OR deleted=0)
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countUnreleased(@Param("scope") ResourceCommandScopeRow scope, @Param("holdNo") String holdNo,
                @Param("includeDeleted") boolean includeDeleted);

    }

    /** 资源持有分配历史明细数据访问；完整仲裁由后续服务编排。 */
    public interface BookingHoldUnitMapper extends BaseMapper<MchBookingHoldUnit> {
        /** Missing bucket identity cannot be inferred as free; conservatively stop the entity until repair. */
        @Select("SELECT EXISTS(SELECT 1 FROM mch_booking_hold_unit u LEFT JOIN mch_resource_bucket b "
                + "ON b.tenant_no=u.tenant_no AND b.entity_no=u.entity_no AND b.bucket_no=u.bucket_no "
                + "LEFT JOIN mch_booking_hold h ON h.tenant_no=u.tenant_no AND h.entity_no=u.entity_no "
                + "AND h.hold_no=u.hold_no LEFT JOIN mch_booking_owner o ON o.tenant_no=h.tenant_no "
                + "AND o.entity_no=h.entity_no AND o.store_no=h.store_no AND o.owner_no=h.owner_no "
                + "WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo} "
                + "AND u.released_at IS NULL AND (u.deleted<>0 OR b.id IS NULL OR b.deleted<>0 "
                + "OR h.id IS NULL OR h.deleted<>0 OR h.status NOT IN ('HELD','CONFIRMED') "
                + "OR o.id IS NULL OR o.deleted<>0 OR o.current_hold_no IS NULL "
                + "OR o.current_hold_no<>h.hold_no OR o.source_revision<>h.source_revision))")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        boolean hasBookingOrphans(@Param("scope") ResourceCommandScopeRow scope);

        /** Owner root serializes discovery; LEFT JOIN exposes corrupt/deleted parent evidence rather than dropping it. */
        @Select("SELECT u.unit_no,u.version,u.bucket_no,b.resource_no,b.start_at,b.end_at,u.qty,u.source_line_ref,"
                + "u.deleted,u.consume_occupation_unit_no,u.consumed_at,u.consume_command_no FROM mch_booking_hold_unit u LEFT JOIN mch_resource_bucket b "
                + "ON b.tenant_no=u.tenant_no AND b.entity_no=u.entity_no AND b.bucket_no=u.bucket_no AND b.deleted=0 "
                + "WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo} "
                + "AND u.hold_no=#{hold} AND u.released_at IS NULL ORDER BY u.id LIMIT 10001")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        List<ResourceBookingAllocationRow> selectBookingAllocations(@Param("scope") ResourceCommandScopeRow scope,
                @Param("hold") String hold);

        /** Unit release is evidence, never deletion; effects are atomic with bucket deltas and the original result. */
        @Update("UPDATE mch_booking_hold_unit SET released_at=#{at},release_command_no=#{command},version=version+1,"
                + "updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND hold_no=#{hold} AND unit_no=#{unit} AND version=#{version} "
                + "AND released_at IS NULL AND consumed_at IS NULL AND consume_occupation_unit_no IS NULL "
                + "AND consume_command_no IS NULL AND deleted=0 AND version<9223372036854775807")
        int releaseBookingUnit(@Param("scope") ResourceCommandScopeRow scope, @Param("hold") String hold,
                @Param("unit") String unit, @Param("version") long version, @Param("command") String command,
                @Param("actor") String actor, @Param("at") LocalDateTime at);
    }

    /**
     * 预约时段。<b>占位与释放只走这里的两条带条件 UPDATE</b>，不要用 Wrapper 或
     * {@code updateById} —— 那两条路都是「先查再改」，并发下必然超约。
     * 与库存锁定（{@code SkuMapper.lockStock}）是同一套手法。
     */
    public interface AppointmentSlotMapper
            extends BaseMapper<MchAppointmentSlot> {

        /**
         * 抢一个名额。
         *
         * <p>条件全写在 WHERE 里：<b>时段属于这家店</b>、还没约满、可约、没被逻辑删。
         *
         * <p>{@code store_no} 这一条是**越权闸不是筛选**：时段编号由端上传，
         * 不比对归属的话，买家可以拿别家店的时段号来下单 ——
         * 占的是别人的名额，而那家店的师傅那天根本不知道有这一单。
         * <b>返回 0 不等于「满了」</b> —— 也可能是停约或这一行压根不存在，
         * 所以调用方要区分「行不存在/已停约」（80015）与「已约满」（80014）：
         * 前者要让买家换一个时段，后者要让他换一个时间，两句话不一样。
         *
         * @return 影响行数；1 = 抢到，0 = 没抢到
         */
        @Update("""
                UPDATE mch_appointment_slot SET booked = booked + 1, version = version + 1
                WHERE slot_no = #{slotNo} AND store_no = #{storeNo} AND deleted = 0
                  AND status = 'OPEN' AND booked < capacity
                """)
        int tryBook(@Param("slotNo") String slotNo, @Param("storeNo") String storeNo);

        /**
         * 还一个名额。
         *
         * <p>{@code booked > 0} 是防线不是装饰：少了它，一次重复释放就能把
         * booked 减成负数，此后这个时段能卖出比 capacity 更多的单，
         * <b>而且不会有任何报错</b>。与 {@code SkuMapper.releaseStock} 的
         * {@code locked_stock >= qty} 同一个位置、同一个理由。
         *
         * <p><b>停约的时段照样能还</b>（WHERE 里不判 status）：商家停约之后，
         * 那些已经约进来的单取消时还得把名额退回去，否则数字永远对不上。
         */
        @Update("""
                UPDATE mch_appointment_slot SET booked = booked - 1, version = version + 1
                WHERE slot_no = #{slotNo} AND deleted = 0 AND booked > 0
                """)
        int release(@Param("slotNo") String slotNo);
    }

    /** Verified effective qualification facts carry their original store scope; no permission inference. */
    public interface PersonnelSkillMapper extends BaseMapper<MchPersonnelSkill> {
        /** Whole rounded bucket eligibility; SKU-specific facts cannot authorize an unspecified SKU. */
        @Select("""
                SELECT COUNT(*) FROM mch_personnel_skill
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND personnel_no=#{personnelNo}
                  AND deleted=0 AND status='VERIFIED' AND (store_no IS NULL OR store_no=#{scope.storeNo})
                  AND goods_no=#{goodsNo} AND (sku_no IS NULL OR (#{skuNo} IS NOT NULL AND sku_no=#{skuNo}))
                  AND valid_from<=#{startAt} AND (valid_to IS NULL OR valid_to>=#{endAt})
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countCoveringQualification(@Param("scope") ResourceCommandScopeRow scope,
                @Param("personnelNo") String personnelNo, @Param("goodsNo") String goodsNo,
                @Param("skuNo") String skuNo, @Param("startAt") LocalDateTime startAt,
                @Param("endAt") LocalDateTime endAt);

        /** Resolve child references through the master identity under the same owner snapshot. */
        @Select("""
                <script>
                SELECT p.personnel_no, k.skill_no, k.store_no, k.goods_no, k.sku_no, k.level_code,
                k.valid_from, k.valid_to FROM mch_personnel_skill k
                JOIN mch_personnel p ON p.tenant_no=k.tenant_no AND p.entity_no=k.entity_no
                AND p.personnel_no=k.personnel_no
                WHERE k.tenant_no=#{tenantNo} AND k.entity_no=#{entityNo} AND k.deleted=0 AND k.status='VERIFIED'
                AND p.deleted=0 AND p.status='ACTIVE'
                AND (k.store_no IS NULL OR k.store_no=#{storeNo})
                AND k.valid_from &lt;= #{at} AND (k.valid_to IS NULL OR k.valid_to>#{at})
                AND k.personnel_no IN
                <foreach collection="personnelNos" item="person" open="(" separator="," close=")">#{person}</foreach>
                ORDER BY k.personnel_no, k.skill_no LIMIT 2001
                </script>
                """)
        List<PersonnelQualificationFactRow> selectEffectiveByPersonnel(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("personnelNos") Set<String> personnelNos, @Param("at") LocalDateTime at);
    }

    /** Canonical resources; a staff resource is shared by all valid store assignments of one personnel. */
    public interface ResourceMapper extends BaseMapper<MchResource> {
        /** Any active local rule must be explicitly retired before employment can activate clipped occurrences. */
        @Select("SELECT COUNT(*) FROM mch_schedule_rule WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND resource_no=#{resource} AND store_no=#{store} AND status='ACTIVE' AND deleted=0")
        long countStoreScheduleRules(@Param("entity") String entity, @Param("store") String store,
                @Param("resource") String resource);

        /** Schedule and physical maintenance share one root revision, including canonical cross-store STAFF. */
        @Update("UPDATE mch_resource SET version=version+1,updated_by=#{actor},updated_at=#{at} "
                + "WHERE tenant_no='MAIN' AND entity_no=#{entity} AND resource_no=#{resource} "
                + "AND version=#{expected} AND version<9223372036854775807 AND deleted=0")
        int advanceScheduleRevision(@Param("entity") String entity, @Param("resource") String resource,
                @Param("expected") long expected, @Param("actor") String actor, @Param("at") LocalDateTime at);

        /** Historical bucket capacities are immutable until a versioned capacity-change protocol exists. */
        @Select("SELECT COUNT(*) FROM mch_resource_bucket WHERE tenant_no='MAIN' "
                + "AND entity_no=#{entity} AND resource_no=#{resource}")
        long countMaintenanceBuckets(@Param("entity") String entity, @Param("resource") String resource);

        /** Physical resource CAS cannot rewrite canonical identity, store or occupation credentials. */
        @Update("UPDATE mch_resource SET name=#{name},capacity=#{capacity},status=#{status},version=version+1,"
                + "updated_by=#{actor},updated_at=#{at} WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND resource_no=#{resource} AND store_no=#{store} AND resource_type IN ('ROOM','SEAT','DEVICE') "
                + "AND version=#{expected} AND version<9223372036854775807 AND deleted=0")
        int saveMaintenance(@Param("entity") String entity, @Param("store") String store,
                @Param("resource") String resource, @Param("expected") long expected, @Param("name") String name,
                @Param("capacity") int capacity, @Param("status") String status, @Param("actor") String actor,
                @Param("at") LocalDateTime at);

        /** Physical resources are store-scoped while staff identity and capacity remain entity-wide. */
        @Select("""
                SELECT * FROM mch_resource WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND resource_no=#{resourceNo} AND deleted=0
                  AND (resource_type='STAFF' OR store_no=#{scope.storeNo})
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResource selectScopedResource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resourceNo") String resourceNo);

        /** Individually acquire canonical sorted roots; ORDER BY on an IN query cannot guarantee lock order. */
        @Select("""
                SELECT * FROM mch_resource WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND resource_no=#{resourceNo} AND deleted=0
                  AND (resource_type='STAFF' OR store_no=#{scope.storeNo}) FOR UPDATE
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResource lockScopedResource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resourceNo") String resourceNo);

        /** Keep joined employment rows to reject overlaps rather than DISTINCT away inconsistent staffing. */
        @Select("""
                <script>
                SELECT r.resource_no,
                CASE WHEN r.resource_type='STAFF' THEN 'STAFF' WHEN r.resource_type='ROOM' THEN 'ROOM'
                WHEN r.resource_type='SEAT' THEN 'SEAT' WHEN r.resource_type='DEVICE' THEN 'DEVICE'
                ELSE r.resource_type END AS resource_type,
                CASE WHEN r.resource_type='STAFF' THEN NULL ELSE s.store_no END AS store_no,
                p.personnel_no, r.name, r.capacity, (r.open_occupation_no IS NOT NULL) AS open_occupied,
                e.tenant_no,e.entity_no,s.store_no AS source_store_no
                FROM mch_resource r
                JOIN mch_entity e ON e.tenant_no=r.tenant_no AND e.entity_no=r.entity_no
                JOIN mch_store s ON s.tenant_no=r.tenant_no AND s.entity_no=r.entity_no AND s.store_no=#{storeNo}
                LEFT JOIN mch_personnel p ON r.resource_type='STAFF' AND p.tenant_no=r.tenant_no
                AND p.entity_no=r.entity_no AND p.personnel_no=r.personnel_no AND p.deleted=0 AND p.status='ACTIVE'
                LEFT JOIN mch_personnel_store a ON r.resource_type='STAFF' AND a.tenant_no=p.tenant_no
                AND a.entity_no=p.entity_no AND a.personnel_no=p.personnel_no AND a.store_no=s.store_no
                AND a.deleted=0 AND a.status='ACTIVE' AND a.valid_from &lt;= #{at}
                AND (a.valid_to IS NULL OR a.valid_to>#{at})
                WHERE r.tenant_no=#{tenantNo} AND r.entity_no=#{entityNo} AND r.deleted=0 AND r.status='ACTIVE'
                AND e.deleted=0 AND e.status='ACTIVE' AND e.archived_at IS NULL AND s.deleted=0 AND s.status='ACTIVE'
                AND ((r.resource_type='STAFF' AND a.assignment_no IS NOT NULL)
                OR (r.resource_type IN ('ROOM','SEAT','DEVICE') AND r.store_no=s.store_no))
                AND r.resource_no IN
                <foreach collection="resourceNos" item="resource" open="(" separator="," close=")">
                #{resource}</foreach>
                ORDER BY r.resource_no, a.assignment_no LIMIT 201
                </script>
                """)
        List<ResourceFactRow> selectEffectiveFacts(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("resourceNos") Set<String> resourceNos, @Param("at") LocalDateTime at);
        /** Owner-scoped enumeration; public staff visibility is filtered before any reference leaves the owner. */
        @Select("""
                SELECT r.resource_no FROM mch_resource r
                JOIN mch_entity e ON e.tenant_no=r.tenant_no AND e.entity_no=r.entity_no
                JOIN mch_store s ON s.tenant_no=r.tenant_no AND s.entity_no=r.entity_no AND s.store_no=#{store}
                WHERE r.tenant_no=#{tenant} AND r.entity_no=#{entity} AND r.deleted=0 AND r.status='ACTIVE'
                  AND e.deleted=0 AND e.status='ACTIVE' AND e.archived_at IS NULL
                  AND s.deleted=0 AND s.status='ACTIVE'
                  AND ((r.resource_type IN ('ROOM','SEAT','DEVICE') AND r.store_no=s.store_no)
                    OR (r.resource_type='STAFF' AND EXISTS (SELECT 1 FROM mch_personnel p
                      JOIN mch_personnel_store a ON a.tenant_no=p.tenant_no AND a.entity_no=p.entity_no
                        AND a.personnel_no=p.personnel_no
                      WHERE p.tenant_no=r.tenant_no AND p.entity_no=r.entity_no AND p.personnel_no=r.personnel_no
                        AND p.deleted=0 AND p.status='ACTIVE' AND a.deleted=0 AND a.status='ACTIVE'
                        AND a.store_no=s.store_no AND a.valid_from<=#{at}
                        AND (a.valid_to IS NULL OR a.valid_to>#{at})
                        AND (#{publicOnly}=FALSE OR a.public_visible=1))))
                ORDER BY r.resource_no LIMIT #{limit}
                """)
        List<String> selectCandidateIds(@Param("tenant") String tenant, @Param("entity") String entity,
                @Param("store") String store, @Param("at") LocalDateTime at,
                @Param("publicOnly") boolean publicOnly, @Param("limit") int limit);

    }

    /** Weekly rules retain all related store assignments for explicit shared-resource conflict checks. */
    public interface ScheduleRuleMapper extends BaseMapper<MchScheduleRule> {
        /** One scoped snapshot; conservative dates precede exact time-zone expansion and employment clipping. */
        @Select("""
                <script>
                SELECT w.rule_no, r.resource_no, s.store_no, q.store_no AS requested_store_no, w.timezone,
                w.weekday_mask, w.start_minute, w.end_minute, w.end_day_offset, w.valid_from, w.valid_to,
                (r.resource_type='STAFF') AS staff, a.assignment_no,
                a.valid_from AS employment_from, a.valid_to AS employment_to
                FROM mch_schedule_rule w
                JOIN mch_resource r ON r.tenant_no=w.tenant_no AND r.entity_no=w.entity_no
                AND r.resource_no=w.resource_no
                JOIN mch_entity e ON e.tenant_no=r.tenant_no AND e.entity_no=r.entity_no
                JOIN mch_store s ON s.tenant_no=w.tenant_no AND s.entity_no=w.entity_no AND s.store_no=w.store_no
                JOIN mch_store q ON q.tenant_no=r.tenant_no AND q.entity_no=r.entity_no AND q.store_no=#{storeNo}
                LEFT JOIN mch_personnel p ON r.resource_type='STAFF' AND p.tenant_no=r.tenant_no
                AND p.entity_no=r.entity_no AND p.personnel_no=r.personnel_no AND p.deleted=0 AND p.status='ACTIVE'
                LEFT JOIN mch_personnel_store a ON r.resource_type='STAFF' AND a.tenant_no=p.tenant_no
                AND a.entity_no=p.entity_no AND a.personnel_no=p.personnel_no AND a.store_no=s.store_no
                AND a.deleted=0 AND a.status='ACTIVE' AND a.valid_from &lt; #{until}
                AND (a.valid_to IS NULL OR a.valid_to>#{from})
                WHERE w.tenant_no=#{tenantNo} AND w.entity_no=#{entityNo} AND w.deleted=0 AND w.status='ACTIVE'
                AND r.deleted=0 AND r.status='ACTIVE' AND e.deleted=0 AND e.status='ACTIVE' AND e.archived_at IS NULL
                AND s.deleted=0 AND s.status='ACTIVE' AND q.deleted=0 AND q.status='ACTIVE'
                AND ((r.resource_type='STAFF' AND a.assignment_no IS NOT NULL)
                OR (r.resource_type IN ('ROOM','SEAT','DEVICE') AND r.store_no=s.store_no AND s.store_no=q.store_no))
                AND w.valid_from &lt;= #{lastAnchor} AND (w.valid_to IS NULL OR w.valid_to>=#{firstAnchor})
                AND r.resource_no IN
                <foreach collection="resourceNos" item="resource" open="(" separator="," close=")">
                #{resource}</foreach>
                ORDER BY r.resource_no, w.rule_no, a.assignment_no LIMIT 2001
                </script>
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        List<ScheduleRuleFactRow> selectWindowRules(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("resourceNos") Set<String> resourceNos, @Param("from") LocalDateTime from,
                @Param("until") LocalDateTime until, @Param("firstAnchor") LocalDate firstAnchor,
                @Param("lastAnchor") LocalDate lastAnchor);
    }

    /** Future owner workflows append change evidence; it does not authorize or execute affected booking changes. */
    public interface ScheduleChangeLogMapper extends BaseMapper<MchScheduleChangeLog> {
    }
}
