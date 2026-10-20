package ai.neargo.shop.pay.mapper.entitlement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Attribution follows the actual consumed entries, including depleted lots; never the latest recharge. */
public interface EntMarketingFactMapper {
    String MONEY_FROM="""
             FROM sv_entry e JOIN sv_voucher v ON v.tenant_no=e.tenant_no AND v.voucher_no=e.voucher_no
             JOIN sv_lot l ON l.tenant_no=e.tenant_no AND l.lot_no=e.lot_no AND l.account_no=v.account_no
             JOIN sv_recharge r ON r.tenant_no=l.tenant_no AND r.recharge_no=l.recharge_no
             WHERE v.tenant_no=#{tenant} AND v.biz_type='CONSUME' AND v.biz_no=#{operation}
             AND r.entity_no=#{entity} AND r.currency=#{currency} AND r.status='CREDITED'
             AND e.deleted=0 AND v.deleted=0 AND l.deleted=0 AND r.deleted=0
            """;
    @Select("SELECT r.activity_no AS activity_no,SUM(-e.principal_delta-e.bonus_delta) AS amount_minor"
            + MONEY_FROM + " AND r.activity_no>#{after} GROUP BY r.activity_no ORDER BY r.activity_no LIMIT #{limit}")
    List<Map<String,Object>> money(@Param("tenant") String tenant,@Param("entity") String entity,
            @Param("operation") String operation,@Param("currency") String currency,
            @Param("after") String after,@Param("limit") int limit);

    @Select("SELECT COALESCE(SUM(-e.principal_delta-e.bonus_delta),0)"+MONEY_FROM)
    BigDecimal moneyTotal(@Param("tenant") String tenant,@Param("entity") String entity,
            @Param("operation") String operation,@Param("currency") String currency);
}
