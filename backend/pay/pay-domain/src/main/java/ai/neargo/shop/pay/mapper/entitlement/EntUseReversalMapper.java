package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntUseReversal;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntUseReversalMapper extends BaseMapper<EntUseReversal> {
    @Select("SELECT * FROM ent_use_reversal WHERE tenant_no=#{tenant} AND reverse_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    EntUseReversal find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM ent_use_reversal WHERE tenant_no=#{tenant} AND reverse_no=#{no} AND deleted=0 FOR UPDATE")
    EntUseReversal lock(@Param("tenant") String tenant, @Param("no") String no);
}
