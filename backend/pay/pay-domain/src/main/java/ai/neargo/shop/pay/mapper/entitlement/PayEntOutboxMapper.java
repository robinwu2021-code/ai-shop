package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.PayEntOutbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PayEntOutboxMapper extends BaseMapper<PayEntOutbox> {
    @Insert("INSERT IGNORE INTO pay_ent_outbox (tenant_no,event_no,event_type,schema_version,aggregate_no,payload,payload_hash,"
            + "occurred_at,status,attempts,next_attempt_at,version,deleted,created_at,updated_at,created_by,updated_by) "
            + "VALUES (#{tenantNo},#{eventNo},#{eventType},#{schemaVersion},#{aggregateNo},#{payload},#{payloadHash},"
            + "#{occurredAt},#{status},#{attempts},#{nextAttemptAt},#{version},#{deleted},#{createdAt},#{updatedAt},"
            + "#{createdBy},#{updatedBy})")
    int insertIfAbsent(PayEntOutbox row);

    @Select("SELECT * FROM pay_ent_outbox WHERE tenant_no=#{tenant} AND event_no=#{eventNo} AND deleted=0 FOR UPDATE")
    PayEntOutbox findByEventNo(@Param("tenant") String tenant, @Param("eventNo") String eventNo);

    @Select("SELECT * FROM pay_ent_outbox WHERE tenant_no=#{tenant} AND status='PENDING' AND deleted=0 "
            + "AND next_attempt_at<=#{now} ORDER BY id LIMIT #{limit}")
    List<PayEntOutbox> selectPending(@Param("tenant") String tenant, @Param("now") LocalDateTime now,
                                     @Param("limit") int limit);

    @Update("UPDATE pay_ent_outbox SET status='SENT',updated_at=#{now},updated_by='SYSTEM',version=version+1 "
            + "WHERE id=#{id} AND tenant_no=#{tenant} AND status='PENDING' AND deleted=0")
    int markForwarded(@Param("id") long id, @Param("tenant") String tenant, @Param("now") LocalDateTime now);

    @Update("UPDATE pay_ent_outbox SET attempts=attempts+1,next_attempt_at=#{next},updated_at=#{now},"
            + "updated_by='SYSTEM',version=version+1 WHERE id=#{id} AND tenant_no=#{tenant} "
            + "AND status='PENDING' AND deleted=0")
    int defer(@Param("id") long id, @Param("tenant") String tenant, @Param("now") LocalDateTime now,
              @Param("next") LocalDateTime next);

    @Update("UPDATE pay_ent_outbox SET status='DEAD',attempts=attempts+1,updated_at=#{now},updated_by='SYSTEM',"
            + "version=version+1 WHERE id=#{id} AND tenant_no=#{tenant} AND status='PENDING' AND deleted=0")
    int markDead(@Param("id") long id, @Param("tenant") String tenant, @Param("now") LocalDateTime now);
}
