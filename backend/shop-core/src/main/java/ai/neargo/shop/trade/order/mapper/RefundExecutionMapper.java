package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** Every execution mutation is serialized by the parent sub-order lock in the source transaction. */
@Mapper
public interface RefundExecutionMapper {
    @Select("SELECT * FROM ord_sub_order WHERE sub_order_no=#{sub} AND deleted=0 FOR UPDATE")
    OrdSubOrder lockSub(String sub);
    @Select("SELECT * FROM ord_after_sale WHERE after_sale_no=#{as} AND deleted=0 FOR UPDATE")
    OrdAfterSale lockAfterSale(String as);
    @Select("SELECT * FROM ord_item WHERE sub_order_no=#{sub} AND deleted=0 ORDER BY id FOR UPDATE")
    List<OrdItem> items(String sub);
    @Select("SELECT * FROM ord_refund_execution WHERE after_sale_no=#{as} AND deleted=0")
    Map<String,Object> execution(String as);
    @Select("SELECT * FROM ord_refund_execution WHERE after_sale_no=#{as} AND deleted=0 FOR UPDATE")
    Map<String,Object> lockExecution(String as);
    @Select("SELECT * FROM ord_refund_execution WHERE sub_order_no=#{sub}"
            + " AND state NOT IN ('COMMITTED','ABORTED') AND deleted=0 ORDER BY id FOR UPDATE")
    List<Map<String,Object>> open(String sub);
    @Insert("INSERT INTO ord_refund_execution(after_sale_no,sub_order_no,state,input_hash,payload_json)"
            + " VALUES(#{as},#{sub},'PREPARED',#{hash},#{payload})")
    int create(@Param("as") String as,@Param("sub") String sub,@Param("hash") String hash,
               @Param("payload") String payload);
    @Update("UPDATE ord_refund_execution SET state=#{next},cash_payment_no=#{payment},net_cash_minor=#{net},"
            + "result_json=#{result},version=version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE after_sale_no=#{as} AND version=#{version} AND state=#{from}")
    int change(@Param("as") String as,@Param("version") long version,@Param("from") String from,
               @Param("next") String next,@Param("payment") String payment,@Param("net") Long net,
               @Param("result") String result);
}
