package ai.neargo.shop.pay.mapper;

import ai.neargo.shop.pay.entity.StlBill;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.Map;

/** Cash intent persistence; every write is inside the short pay preparation/result transaction. */
public interface SplitIntentMapper {
    @Select("SELECT * FROM stl_bill WHERE settle_no=#{no} AND deleted=0 FOR UPDATE")
    StlBill lockBill(String no);
    @Update("UPDATE stl_bill SET points_funding_ticket=NULL,points_funding_ready_at=NULL,version=version+1"
            + " WHERE settle_no=#{settle} AND points_funding_ticket=#{ticket}")
    int clearFunding(@Param("settle") String settle,@Param("ticket") String ticket);
    @Select("SELECT * FROM stl_split_intent WHERE settle_no=#{no} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    Map<String,Object> latest(String no);
    @Select("SELECT * FROM stl_split_intent WHERE request_no=#{no}")
    Map<String,Object> intent(String no);
    @Select("SELECT * FROM stl_split_intent WHERE request_no=#{no} FOR UPDATE")
    Map<String,Object> lockIntent(String no);
    @Insert("INSERT INTO stl_split_intent(request_no,settle_no,command_json,state,points_ticket,funding_state)"
            + " VALUES(#{request},#{settle},#{command},'READY',#{ticket},#{funding})")
    int insert(@Param("request") String request,@Param("settle") String settle,@Param("command") String command,
               @Param("ticket") String ticket,@Param("funding") String funding);
    @Update("UPDATE stl_split_intent SET state=#{next},version=version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE request_no=#{no} AND state=#{expected}")
    int state(@Param("no") String no,@Param("expected") String expected,@Param("next") String next);
    @Update("UPDATE stl_split_intent SET funding_state=#{funding},version=version+1,updated_at=CURRENT_TIMESTAMP"
            + " WHERE request_no=#{no}")
    int funding(@Param("no") String no,@Param("funding") String funding);
}
