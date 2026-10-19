package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bound exclusively to the payment SqlSessionFactory. */
public interface EntInstanceMapper extends BaseMapper<EntInstance> {
    @Select("SELECT * FROM ent_instance WHERE tenant_no=#{tenant} AND instance_no=#{no} AND deleted=0 FOR UPDATE")
    EntInstance lock(@Param("tenant") String tenant, @Param("no") String no);
}
