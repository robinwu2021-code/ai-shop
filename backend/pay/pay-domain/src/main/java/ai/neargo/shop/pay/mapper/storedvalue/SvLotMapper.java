package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SvLotMapper extends BaseMapper<SvLot> {
    @Select("SELECT * FROM sv_lot WHERE tenant_no=#{tenant} AND account_no=#{account} AND deleted=0 "
            + "AND (principal_minor>0 OR bonus_minor>0) ORDER BY credited_at,lot_no")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<SvLot> live(@Param("tenant") String tenant, @Param("account") String account);
    @Select("SELECT * FROM sv_lot WHERE tenant_no=#{tenant} AND account_no=#{account} AND deleted=0 "
            + "AND (principal_minor>0 OR bonus_minor>0) ORDER BY credited_at,lot_no FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<SvLot> lockLive(@Param("tenant") String tenant, @Param("account") String account);
}
