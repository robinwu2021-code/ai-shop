package ai.neargo.shop.pay.mapper.entitlement;

import ai.neargo.shop.pay.entitlement.entity.PayEntInbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PayEntInboxMapper extends BaseMapper<PayEntInbox> {
    @Insert("INSERT IGNORE INTO pay_ent_inbox (tenant_no,consumer,event_no,payload_hash,processed_at,version,deleted,"
            + "created_at,updated_at,created_by,updated_by) VALUES (#{tenantNo},#{consumer},#{eventNo},#{payloadHash},"
            + "#{processedAt},#{version},#{deleted},#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})")
    int insertIfAbsent(PayEntInbox row);

    @Select("SELECT * FROM pay_ent_inbox WHERE tenant_no=#{tenant} AND consumer=#{consumer} AND event_no=#{eventNo} "
            + "AND deleted=0 FOR UPDATE")
    PayEntInbox lock(@Param("tenant") String tenant, @Param("consumer") String consumer,
                     @Param("eventNo") String eventNo);
}
