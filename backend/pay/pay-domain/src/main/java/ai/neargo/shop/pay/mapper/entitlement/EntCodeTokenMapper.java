package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntCodeToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EntCodeTokenMapper extends BaseMapper<EntCodeToken> {
    @Select("""
            SELECT * FROM ent_code_token WHERE tenant_no=#{tenant} AND user_no=#{user}
                AND request_key_hash=#{key} AND deleted=0
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    EntCodeToken findRequest(@Param("tenant") String tenant, @Param("user") String user, @Param("key") String key);
    @Select("SELECT * FROM ent_code_token WHERE tenant_no=#{tenant} AND token_hash=#{hash} AND deleted=0 FOR UPDATE")
    EntCodeToken lock(@Param("tenant") String tenant, @Param("hash") String hash);
}
