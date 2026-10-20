package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

/** Bound exclusively to the payment SqlSessionFactory. */
public interface EntLedgerMapper extends BaseMapper<EntLedger> {
    @Select("SELECT COALESCE(SUM(units_delta),0) AS units,COALESCE(SUM(reserved_delta),0) AS reserved,"
            + "COALESCE(MAX(asset_seq),0) AS seq FROM ent_ledger WHERE tenant_no=#{tenant} AND instance_no=#{no} AND deleted=0")
    Map<String,Object> totals(@Param("tenant") String tenant,
                               @Param("no") String no);
}
