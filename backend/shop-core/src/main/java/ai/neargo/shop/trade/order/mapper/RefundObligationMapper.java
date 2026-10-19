package ai.neargo.shop.trade.order.mapper;

import ai.neargo.shop.trade.entity.OrdOrder;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RefundObligationMapper {
    @Insert("INSERT INTO ord_refund_coupon_scope(order_no,coupon_no,payload_json,input_hash) VALUES(#{order},#{coupon},#{payload},#{hash})")
    int freezeScope(@Param("order") String order,@Param("coupon") String coupon,@Param("payload") String payload,@Param("hash") String hash);
    @Select("SELECT * FROM ord_refund_coupon_scope WHERE order_no=#{order}")
    Map<String,Object> scope(String order);
    @Select("SELECT * FROM ord_order WHERE order_no=#{order} AND deleted=0 FOR UPDATE")
    OrdOrder lockPayment(String order);
    @Select("SELECT status FROM ord_sub_order WHERE sub_order_no=#{sub} AND deleted=0")
    String childStatus(String sub);
    @Select("SELECT COUNT(*) FROM ord_item WHERE sub_order_no=#{sub} AND deleted=0")
    int itemCount(String sub);
    @Select("SELECT COUNT(*) FROM ord_item WHERE sub_order_no=#{sub} AND deleted=0 AND (refunded_qty IS NULL OR refunded_qty<qty)")
    int unrefundedItems(String sub);
    @Select("SELECT * FROM ord_refund_obligation WHERE execution_no=#{no} ORDER BY id FOR UPDATE")
    List<Map<String,Object>> lockRows(String no);
    @Select("SELECT * FROM ord_refund_obligation WHERE execution_no=#{no} ORDER BY id")
    List<Map<String,Object>> rows(String no);
    @Insert("INSERT INTO ord_refund_obligation(execution_no,kind,source_no,input_hash,payload_json,state,authority_proof)"
        + " VALUES(#{no},'COUPON',#{source},#{hash},#{payload},#{state},#{proof})")
    int create(@Param("no") String no,@Param("source") String source,@Param("hash") String hash,
        @Param("payload") String payload,@Param("state") String state,@Param("proof") String proof);
    @Update("UPDATE ord_refund_obligation SET state=#{state},authority_proof=#{proof},error_code=#{error},"
        + "next_attempt_at=#{next},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND input_hash=#{hash}"
        + " AND state NOT IN ('SUCCEEDED','NOT_REQUIRED')")
    int update(@Param("id") long id,@Param("hash") String hash,@Param("state") String state,
        @Param("proof") String proof,@Param("error") String error,@Param("next") long next);
}
