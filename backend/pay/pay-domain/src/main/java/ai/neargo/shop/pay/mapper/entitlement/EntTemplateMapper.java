package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Owned head locking serializes publication; conditional writes preserve UTC and the trusted actor. */
public interface EntTemplateMapper extends BaseMapper<EntTemplate> {
    /** Explicit tenant/owner predicates are required even when no user security context is present. */
    @Select("SELECT * FROM ent_template WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND template_no=#{no} AND deleted=0")
    EntTemplate findOwned(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no);

    /** Explicit tenant/owner predicates are required even when no user security context is present. */
    @Select("SELECT * FROM ent_template WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND template_no=#{no} AND deleted=0 FOR UPDATE")
    EntTemplate lockOwned(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no);

    /** Compare-and-swap the editable draft without changing a published snapshot. */
    @Update("UPDATE ent_template SET title=#{title},draft_snapshot=#{snapshot},version=version+1, "
            + "updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND template_no=#{no} AND version=#{revision} AND deleted=0 AND status<>'ENDED'")
    int saveDraft(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no,
                  @Param("revision") long revision, @Param("title") String title,
                  @Param("snapshot") String snapshot, @Param("actor") String actor,
                  @Param("at") LocalDateTime at);

    /** Advance the locked head only after inserting the immutable version in the same transaction. */
    @Update("UPDATE ent_template SET current_version_no=#{versionNo},status='ACTIVE',version=version+1, "
            + "updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{tenant} AND entity_no=#{entity} "
            + "AND template_no=#{no} AND version=#{revision} AND deleted=0 AND status<>'ENDED'")
    int publish(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no,
                @Param("revision") long revision, @Param("versionNo") String versionNo,
                @Param("actor") String actor, @Param("at") LocalDateTime at);

    /** State changes do not revoke historical rights and preserve the trusted audit actor. */
    @Update("UPDATE ent_template SET status=#{status},version=version+1,updated_by=#{actor},updated_at=#{at} "
            + "WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND template_no=#{no} "
            + "AND version=#{revision} AND deleted=0 AND status<>'ENDED'")
    int changeStatus(@Param("tenant") String tenant, @Param("entity") String entity, @Param("no") String no,
                     @Param("revision") long revision, @Param("status") String status,
                     @Param("actor") String actor, @Param("at") LocalDateTime at);
}
