package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntTemplateVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Published snapshots are append-only; head lock guards sequential version allocation. */
public interface EntTemplateVersionMapper extends BaseMapper<EntTemplateVersion> {
    /** Must be called while holding the owning template head lock. */
    @Select("SELECT COALESCE(MAX(version_index),0) FROM ent_template_version "
            + "WHERE tenant_no=#{tenant} AND template_no=#{no}")
    int lastIndex(@Param("tenant") String tenant, @Param("no") String no);
}
