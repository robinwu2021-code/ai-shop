package ai.neargo.shop.pay.mapper.points;
import org.apache.ibatis.annotations.*;
import java.util.*;
public interface PointsAdjustmentMapper {
 @Select("SELECT * FROM pts_user_adjustment WHERE adjustment_no=#{no} FOR UPDATE") Map<String,Object> lock(String no);
 @Insert("INSERT INTO pts_user_adjustment(adjustment_no,type_code,submitted_by,payload_json) VALUES(#{no},#{type},#{actor},#{payload})")
 int insert(@Param("no") String no,@Param("type") String type,@Param("actor") String actor,@Param("payload") String payload);
 @Update("UPDATE pts_user_adjustment SET state=#{state},approved_by=#{actor},version=version+1,updated_at=CURRENT_TIMESTAMP WHERE adjustment_no=#{no} AND version=#{version} AND state='SUBMITTED'")
 int decide(@Param("no") String no,@Param("version") long version,@Param("state") String state,@Param("actor") String actor);
 @Select("SELECT * FROM pts_user_adjustment WHERE type_code=#{type} AND id>#{after} ORDER BY id LIMIT #{limit}")
 List<Map<String,Object>> page(@Param("type") String type,@Param("after") long after,@Param("limit") int limit);
}
