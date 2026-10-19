package ai.neargo.shop.product.mapper;

import ai.neargo.shop.product.entity.PrdEntBindingCommand;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PrdEntBindingCommandMapper extends BaseMapper<PrdEntBindingCommand> {
    @Select("SELECT * FROM prd_ent_binding_command WHERE tenant_no=#{tenant} AND request_key=#{key} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    PrdEntBindingCommand find(@Param("tenant") String tenant, @Param("key") String key);
    @Select("SELECT * FROM prd_ent_binding_command WHERE tenant_no=#{tenant} AND request_key=#{key} "
            + "AND deleted=0 FOR UPDATE")
    PrdEntBindingCommand lock(@Param("tenant") String tenant, @Param("key") String key);
    @Insert("INSERT IGNORE INTO prd_ent_binding_command(tenant_no,request_key,payload_hash,status,created_at,"
            + "updated_at,created_by,updated_by) VALUES(#{tenantNo},#{requestKey},#{payloadHash},'PENDING',"
            + "#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int claim(PrdEntBindingCommand row);
}
