package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvRechargeMapper extends BaseMapper<SvRecharge> {
    @Select("SELECT * FROM sv_recharge WHERE tenant_no=#{tenant} AND recharge_no=#{no} AND deleted=0")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    SvRecharge find(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM sv_recharge WHERE tenant_no=#{tenant} AND recharge_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    SvRecharge lock(@Param("tenant") String tenant, @Param("no") String no);
}
