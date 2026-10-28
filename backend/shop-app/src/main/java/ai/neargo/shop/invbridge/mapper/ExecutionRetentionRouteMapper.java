package ai.neargo.shop.invbridge.mapper;

import ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Exact scoped lookup, immutable insertion and overflow-safe whole-observation CAS only. */
@Mapper
public interface ExecutionRetentionRouteMapper {
    @Insert("""
        INSERT INTO prd_execution_retention_route (
            tenant_no, entity_no, store_no, execution_no,
            unit_no, grant_no, attempt_no, prepared_grant_revision,
            route_no, route_hash, retention_no, request_hash,
            reference_hash, parent_original_hash, parent_manifest_hash, stock_confirmation_operation_no,
            source_evidence_hash, component_roster_hash, accepted_intent_no, accepted_intent_hash,
            original_human_authority_hash, resource_command_hash, plan_hash, route_schema,
            reference_schema, frozen_authority, source_confirmation_no, resource_command_no,
            component_roster_count, route_json, reference_json, parent_original_json,
            source_evidence_json, component_roster_json, accepted_intent_json, original_human_authority_json,
            mirror_original_json, mirror_original_hash, state, observation_epoch,
            revision, observations_json, observations_hash, next_retry_at,
            created_at, created_by, updated_at, updated_by,
            version, deleted)
        VALUES (
            #{tenantNo}, #{entityNo}, #{storeNo}, #{executionNo},
            #{unitNo}, #{grantNo}, #{attemptNo}, #{preparedGrantRevision},
            #{routeNo}, #{routeHash}, #{retentionNo}, #{requestHash},
            #{referenceHash}, #{parentOriginalHash}, #{parentManifestHash}, #{stockConfirmationOperationNo},
            #{sourceEvidenceHash}, #{componentRosterHash}, #{acceptedIntentNo}, #{acceptedIntentHash},
            #{originalHumanAuthorityHash}, #{resourceCommandHash}, #{planHash}, #{routeSchema},
            #{referenceSchema}, #{frozenAuthority}, #{sourceConfirmationNo}, #{resourceCommandNo},
            #{componentRosterCount}, #{routeJson}, #{referenceJson}, #{parentOriginalJson},
            #{sourceEvidenceJson}, #{componentRosterJson}, #{acceptedIntentJson}, #{originalHumanAuthorityJson},
            #{mirrorOriginalJson}, #{mirrorOriginalHash}, #{state}, #{observationEpoch},
            #{revision}, #{observationsJson}, #{observationsHash}, #{nextRetryAt},
            #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy},
            #{version}, #{deleted})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PrdExecutionRetentionRoute row);

    @Select("SELECT * FROM prd_execution_retention_route WHERE tenant_no=#{tenant} AND route_no=#{route}")
    PrdExecutionRetentionRoute find(@Param("tenant") String tenant, @Param("route") String route);

    @Select("SELECT * FROM prd_execution_retention_route WHERE tenant_no=#{tenant} AND accepted_intent_no=#{intent}")
    PrdExecutionRetentionRoute findIntent(@Param("tenant") String tenant, @Param("intent") String intent);

    @Select("""
        SELECT * FROM prd_execution_retention_route
        WHERE state='RETAIN_PENDING' AND next_retry_at<=#{now}
          AND (next_retry_at>#{after} OR (next_retry_at=#{after} AND id>#{id}))
        ORDER BY next_retry_at,id LIMIT #{limit}
        """)
    List<PrdExecutionRetentionRoute> due(@Param("now") LocalDateTime now, @Param("after") LocalDateTime after,
            @Param("id") long id, @Param("limit") int limit);

    @Update("""
        UPDATE prd_execution_retention_route
        SET state=#{state}, observations_json=#{observation}, observations_hash=#{hash},
            observation_epoch=observation_epoch+1, revision=revision+1, version=version+1,
            next_retry_at=#{retry}, updated_at=#{now}, updated_by='SYSTEM:RETENTION'
        WHERE id=#{row.id} AND route_hash=#{row.routeHash} AND request_hash=#{row.requestHash}
          AND revision=#{row.revision} AND observation_epoch=#{row.observationEpoch}
          AND revision<9223372036854775806 AND observation_epoch<9223372036854775806
          AND version<9223372036854775806 AND state IN ('RETAIN_PENDING','RETAINED')
        """)
    int observe(@Param("row") PrdExecutionRetentionRoute row, @Param("state") String state,
            @Param("observation") String observation, @Param("hash") String hash,
            @Param("retry") LocalDateTime retry, @Param("now") LocalDateTime now);
}
