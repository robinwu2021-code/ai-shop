package ai.neargo.shop.pay.mapper.points;
import org.apache.ibatis.annotations.*;
import java.util.*;
public interface StatementConfirmationMapper {
 @Update("UPDATE pts_statement_confirmation SET state='SUPERSEDED' WHERE statement_no=#{no} AND revision<#{revision} AND state IN ('DRAFT','DISPUTED')")
 void supersede(@Param("no")String no,@Param("revision")long revision);
 @Insert("INSERT INTO pts_statement_confirmation(statement_no,revision,type_code,merchant_no,store_no,state,published_at) VALUES(#{no},#{revision},#{type},#{merchant},#{store},#{state},#{at}) ON DUPLICATE KEY UPDATE state=#{state}")
 void publish(@Param("no")String no,@Param("revision")long revision,@Param("type")String type,@Param("merchant")String merchant,@Param("store")String store,@Param("state")String state,@Param("at")long at);
 @Select("SELECT * FROM pts_statement_confirmation WHERE statement_no=#{no} AND revision=#{revision} FOR UPDATE") Map<String,Object> lock(@Param("no")String no,@Param("revision")long revision);
 @Select("SELECT c.* FROM pts_statement_confirmation c JOIN pts_store_statement s ON s.position_no=c.statement_no WHERE c.type_code=#{type} AND c.state='DRAFT' AND c.notified_at IS NULL AND s.state='DRAFT' AND s.deleted=0 ORDER BY c.last_attempt_at,c.id LIMIT #{limit}")
 List<Map<String,Object>> pending(@Param("type")String type,@Param("limit")int limit);
 @Select("SELECT c.* FROM pts_statement_confirmation c JOIN pts_store_statement s ON s.position_no=c.statement_no WHERE c.type_code=#{type} AND c.state='DRAFT' AND c.notified_at IS NOT NULL AND c.deadline<=#{now} AND s.state='DRAFT' AND s.deleted=0 ORDER BY c.last_attempt_at,c.deadline,c.id LIMIT #{limit}")
 List<Map<String,Object>> due(@Param("type")String type,@Param("now")long now,@Param("limit")int limit);
 @Update("UPDATE pts_statement_confirmation SET notified_at=#{at},deadline=#{deadline} WHERE statement_no=#{no} AND revision=#{revision} AND state='DRAFT' AND notified_at IS NULL")
 int notified(@Param("no")String no,@Param("revision")long revision,@Param("at")long at,@Param("deadline")long deadline);
 @Update("UPDATE pts_statement_confirmation SET last_attempt_at=#{now} WHERE statement_no=#{no} AND revision=#{revision}")
 void attempted(@Param("no")String no,@Param("revision")long revision,@Param("now")long now);
 @Update("UPDATE pts_statement_confirmation SET state='CONFIRMED' WHERE statement_no=#{no} AND revision=#{revision} AND state='DRAFT'") int done(@Param("no")String no,@Param("revision")long revision);
}
