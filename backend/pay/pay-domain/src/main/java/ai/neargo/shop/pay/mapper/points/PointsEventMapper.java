package ai.neargo.shop.pay.mapper.points;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.Map;

/** Metadata only; financial changes remain exclusive to posting. */
@Mapper
public interface PointsEventMapper {
    @Select("SELECT * FROM pts_cursor WHERE task_name=#{task} AND deleted=0")
    Map<String,Object> cursor(String task);
    @Select("SELECT * FROM pts_cursor WHERE task_name=#{task} AND deleted=0 FOR UPDATE")
    Map<String,Object> lockCursor(String task);
    @Insert("INSERT INTO pts_cursor(type_code,state,payload_json,task_name,watermark)"
            + " VALUES(#{type},'ACTIVE',#{body},#{task},#{next})")
    int insertCursor(@Param("task") String task,@Param("type") String type,@Param("next") long next,
                     @Param("body") String body);
    @Update("UPDATE pts_cursor SET watermark=#{next},payload_json=#{body},version=version+1"
            + " WHERE task_name=#{task} AND watermark=#{expected} AND deleted=0")
    int advanceCursor(@Param("task") String task,@Param("expected") long expected,@Param("next") long next,
                      @Param("body") String body);
    @Select("SELECT * FROM pts_outbox WHERE event_no=#{event} AND deleted=0 FOR UPDATE")
    Map<String,Object> event(String event);
    @Insert("INSERT INTO pts_outbox(type_code,state,payload_json,event_no) VALUES(#{type},'READY',#{body},#{event})")
    int insert(@Param("type") String type,@Param("event") String event,@Param("body") String body);
    @Select("SELECT * FROM pts_outbox WHERE state IN ('READY','RETRY','PROCESSING')"
            + " AND (#{type,jdbcType=VARCHAR} IS NULL OR type_code=#{type,jdbcType=VARCHAR})"
            + " AND next_attempt<=#{now} AND deleted=0 ORDER BY next_attempt,id LIMIT 1 FOR UPDATE")
    Map<String,Object> next(@Param("type") String type,@Param("now") long now);
    @Select("SELECT * FROM pts_outbox WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Map<String,Object> lock(long id);
    @Update("UPDATE pts_outbox SET state=#{state},next_attempt=#{next},retry_count=#{attempt},"
            + "version=version+1 WHERE id=#{id} AND version=#{version} AND deleted=0")
    int transition(@Param("id") long id,@Param("version") long version,@Param("state") String state,
                   @Param("next") long next,@Param("attempt") int attempt);
    @Insert("INSERT INTO pts_inbox(type_code,state,payload_json,event_no,payload_hash)"
            + " VALUES(#{type},'AUDIT',#{body},#{event},#{hash})")
    int audit(@Param("type") String type,@Param("event") String event,@Param("body") String body,
              @Param("hash") String hash);
}
