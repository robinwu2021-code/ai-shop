package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entitlement.entity.OrdEntOrderUse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface EntitlementOrderUseMapper extends BaseMapper<OrdEntOrderUse> {
    @Select("SELECT * FROM ord_ent_order_use WHERE tenant_no=#{tenant} AND operation_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    OrdEntOrderUse find(@Param("tenant") String tenant, @Param("no") String no);
}
