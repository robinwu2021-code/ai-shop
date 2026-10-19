package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** Cash execution and points refund workflow share the production execution table. */
@Mapper
public interface RefundExecutionMapper extends BaseMapper<OrdRefundExecution> {
    @Select("SELECT e.after_sale_no FROM ord_points_refund_execution e"
            + " JOIN ord_after_sale a ON a.after_sale_no=e.after_sale_no AND a.deleted=0 AND a.status='REFUNDING'"
            + " LEFT JOIN ord_points_refund_binding p ON p.after_sale_no=e.after_sale_no"
            + " AND p.phase IN ('COMPENSATED_PENDING_OPERATOR','CASH_FAILED_PENDING_OPERATOR')"
            + " WHERE e.deleted=0 AND e.state<>'ABORTED' AND p.after_sale_no IS NULL"
            + " ORDER BY COALESCE(e.last_attempt_at,0),e.id LIMIT #{limit} FOR UPDATE")
    List<String> pendingNos(int limit);

    @Update("UPDATE ord_points_refund_execution SET last_attempt_at=#{at} WHERE after_sale_no=#{as}")
    int attempted(@Param("as") String as, @Param("at") long at);

    @Select("SELECT * FROM ord_sub_order WHERE sub_order_no=#{sub} AND deleted=0 FOR UPDATE")
    OrdSubOrder lockSub(String sub);

    @Select("SELECT * FROM ord_after_sale WHERE after_sale_no=#{as} AND deleted=0 FOR UPDATE")
    OrdAfterSale lockAfterSale(String as);

    @Select("SELECT * FROM ord_item WHERE sub_order_no=#{sub} AND deleted=0 ORDER BY id FOR UPDATE")
    List<OrdItem> items(String sub);

    @Select("SELECT * FROM ord_points_refund_execution WHERE after_sale_no=#{as} AND deleted=0")
    Map<String, Object> execution(String as);

    @Select("SELECT * FROM ord_points_refund_execution WHERE after_sale_no=#{as} AND deleted=0 FOR UPDATE")
    Map<String, Object> lockExecution(String as);

    @Select("SELECT * FROM ord_points_refund_execution WHERE sub_order_no=#{sub}"
            + " AND state NOT IN ('COMMITTED','ABORTED') AND deleted=0 ORDER BY id FOR UPDATE")
    List<Map<String, Object>> open(String sub);

    @Insert("INSERT INTO ord_points_refund_execution(after_sale_no,sub_order_no,state,input_hash,payload_json)"
            + " VALUES(#{as},#{sub},'PREPARED',#{hash},#{payload})")
    int create(@Param("as") String as, @Param("sub") String sub, @Param("hash") String hash,
               @Param("payload") String payload);

    @Update("UPDATE ord_points_refund_execution SET closure_version=#{protocol},return_coupon=#{coupon}"
            + " WHERE after_sale_no=#{as} AND state='PREPARED' AND version=0")
    int pinClosure(@Param("as") String as,@Param("protocol") int protocol,@Param("coupon") boolean coupon);

    @Update("UPDATE ord_points_refund_execution SET state=#{next},cash_payment_no=#{payment},net_cash_minor=#{net},"
            + "result_json=#{result},version=version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE after_sale_no=#{as} AND version=#{version} AND state=#{from}")
    int change(@Param("as") String as, @Param("version") long version, @Param("from") String from,
               @Param("next") String next, @Param("payment") String payment, @Param("net") Long net,
               @Param("result") String result);
}
