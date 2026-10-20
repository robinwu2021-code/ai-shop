package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvHoldAllocation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvHoldAllocationMapper extends BaseMapper<SvHoldAllocation> {
    @Select("SELECT * FROM sv_hold_allocation WHERE tenant_no=#{tenant} AND operation_no=#{operation} AND deleted=0 "
            + "ORDER BY lot_no FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<SvHoldAllocation> lockOperation(@Param("tenant") String tenant, @Param("operation") String operation);
}
