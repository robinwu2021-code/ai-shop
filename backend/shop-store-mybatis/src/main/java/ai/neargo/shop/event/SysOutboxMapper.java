package ai.neargo.shop.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Outbox 是基础设施表，各域共写，不参与模块归属划分。 */
public interface SysOutboxMapper extends BaseMapper<SysOutbox> {
    @Insert("INSERT IGNORE INTO sys_outbox (event_no,aggregate_type,aggregate_id,event_type,payload,status,retry_count,created_at) "
            + "VALUES (#{eventNo},#{aggregateType},#{aggregateId},#{eventType},#{payload},#{status},#{retryCount},#{createdAt})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int insertIfAbsent(SysOutbox row);

    @Select("SELECT * FROM sys_outbox WHERE event_no=#{eventNo} LIMIT 1")
    SysOutbox findByEventNo(@Param("eventNo") String eventNo);
}
