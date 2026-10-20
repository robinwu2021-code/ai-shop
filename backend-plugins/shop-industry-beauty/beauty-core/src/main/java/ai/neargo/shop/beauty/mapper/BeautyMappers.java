package ai.neargo.shop.beauty.mapper;

import ai.neargo.shop.beauty.entity.BtyStorePolicy;
import ai.neargo.shop.beauty.entity.BtyStorePolicyVersion;
import ai.neargo.shop.beauty.entity.BtyServiceDefinition;
import ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
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
}
