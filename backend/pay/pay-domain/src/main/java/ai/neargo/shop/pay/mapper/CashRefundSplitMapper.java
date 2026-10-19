package ai.neargo.shop.pay.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Every mutation is serialized by the corresponding settlement bill lock. */
public interface CashRefundSplitMapper {
    @Select("SELECT * FROM stl_cash_refund_reverse WHERE after_sale_no=#{no}")
    Map<String,Object> find(String no);
    @Select("SELECT COUNT(*) FROM stl_cash_refund_reverse WHERE settle_no=#{no} AND state<>'CONFIRMED'")
    long pending(String no);
    @Insert("INSERT INTO stl_cash_refund_reverse(after_sale_no,settle_no,request_no,command_json,state,pay_channel)"
            +" VALUES(#{as},#{settle},#{request},#{json},'DISPATCHING',#{channel})")
    int create(@Param("as") String as,@Param("settle") String settle,@Param("request") String request,
               @Param("json") String json,@Param("channel") String channel);
    @Update("UPDATE stl_cash_refund_reverse SET state=#{state},provider_no=#{provider},last_error=#{error},"
            +"version=version+1,updated_at=CURRENT_TIMESTAMP WHERE after_sale_no=#{as} AND state<>'CONFIRMED'")
    int result(@Param("as") String as,@Param("state") String state,@Param("provider") String provider,
               @Param("error") String error);
}
