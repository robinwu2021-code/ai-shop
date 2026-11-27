package ai.neargo.shop.event;

import ai.neargo.shop.common.config.CommonTableMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 发件箱是公共能力，表在基础能力库（ADR-067 决策 10）：挂 common 句柄，各域共写。 */
public interface SysOutboxMapper extends CommonTableMapper<SysOutbox> {
    @Insert("INSERT IGNORE INTO sys_outbox (event_no,aggregate_type,aggregate_id,event_type,payload,status,retry_count,created_at) "
            + "VALUES (#{eventNo},#{aggregateType},#{aggregateId},#{eventType},#{payload},#{status},#{retryCount},#{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertIfAbsent(SysOutbox row);

    @Select("SELECT * FROM sys_outbox WHERE event_no=#{eventNo} LIMIT 1")
    SysOutbox findByEventNo(@Param("eventNo") String eventNo);
}
