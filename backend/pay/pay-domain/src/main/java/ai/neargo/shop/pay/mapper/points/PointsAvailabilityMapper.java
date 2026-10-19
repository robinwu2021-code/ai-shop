package ai.neargo.shop.pay.mapper.points;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** One current projection row per type; only rebuilds read historical pages. */
public interface PointsAvailabilityMapper {
    @Select("SELECT market FROM pts_point_type WHERE type_code=#{type} AND deleted=0 FOR UPDATE")
    Map<String,Object> lockType(String type);
    @Select("SELECT type_code FROM pts_point_type WHERE deleted=0 ORDER BY type_code")
    List<String> types();
    @Select("SELECT * FROM pts_availability_projection WHERE type_code=#{type} FOR UPDATE")
    Map<String,Object> snapshot(String type);
    @Select("SELECT * FROM pts_merchant_agreement WHERE type_code=#{type} AND deleted=0 AND id>#{after}"
            + " AND state IN ('ACTIVE','TERMINATED') ORDER BY id LIMIT #{limit}")
    List<Map<String,Object>> agreements(@Param("type") String type,@Param("after") long after,@Param("limit") int limit);
    @Select("SELECT * FROM pts_pause_interval WHERE (type_code=#{type} OR type_code='*') AND deleted=0"
            + " AND id>#{after} AND scope NOT IN ('USER','REDEEM_USER','FREEZE','EARN_USER')"
            + " ORDER BY id LIMIT #{limit}")
    List<Map<String,Object>> pauses(@Param("type") String type,@Param("after") long after,@Param("limit") int limit);
    @Insert("INSERT INTO pts_availability_projection(type_code,source_version,projected_version,payload_json)"
            + " VALUES(#{type},1,0,'[]') ON DUPLICATE KEY UPDATE source_version=source_version+1,updated_at=CURRENT_TIMESTAMP")
    int dirty(String type);
    @Insert("INSERT INTO pts_availability_projection(type_code,source_version,projected_version,payload_json)"
            + " VALUES(#{type},#{version},#{version},#{body})")
    int insert(@Param("type") String type,@Param("version") long version,@Param("body") String body);
    @Update("UPDATE pts_availability_projection SET source_version=#{version},projected_version=#{version},"
            + "payload_json=#{body},updated_at=CURRENT_TIMESTAMP WHERE type_code=#{type}")
    int save(@Param("type") String type,@Param("version") long version,@Param("body") String body);
}
