package ai.neargo.shop.pay.mapper.storedvalue;

import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Historical statements keep their original identity; ownership follows trusted account binding. */
public interface SvConsumerStatementMapper {
    String OWNED = "s.tenant_no=#{tenant} AND s.store_no=#{store} AND s.deleted=0 AND "
            + "EXISTS (SELECT 1 FROM sv_account a WHERE a.tenant_no=s.tenant_no AND a.account_no=s.account_no "
            + "AND a.entity_no=s.entity_no AND a.store_no=s.store_no AND a.deleted=0 AND (a.user_no=#{user} OR "
            + "EXISTS (SELECT 1 FROM sv_customer_identity i WHERE i.tenant_no=a.tenant_no "
            + "AND i.customer_no=a.customer_no AND i.user_no=#{user})))";
    @Select("SELECT s.* FROM sv_statement s WHERE " + OWNED
            + " AND s.type=#{type} AND s.business_no=#{business} LIMIT 1")
    SvStatement focus(@Param("tenant") String tenant, @Param("user") String user,
            @Param("store") String store, @Param("type") String type, @Param("business") String business);

    @Select("<script>SELECT s.* FROM sv_statement s WHERE " + OWNED
            + " AND s.type IN ('RECHARGE','REDEMPTION')"
            + "<if test='type != &quot;ALL&quot;'> AND s.type=#{type}</if>"
            + "<if test='from != null'> AND s.occurred_at &gt;=#{from}</if>"
            + "<if test='to != null'> AND s.occurred_at &lt;#{to}</if>"
            + "<if test='at != null'> AND (s.occurred_at &lt;#{at} OR (s.occurred_at=#{at} "
            + "AND s.record_no &lt;#{key})<if test='inclusive'> OR (s.occurred_at=#{at} AND s.record_no=#{key})</if>)</if>"
            + " ORDER BY s.occurred_at DESC,s.record_no DESC LIMIT #{limit}</script>")
    List<SvStatement> page(@Param("tenant") String tenant, @Param("user") String user,
            @Param("store") String store, @Param("type") String type, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to, @Param("at") LocalDateTime at, @Param("key") String key,
            @Param("inclusive") boolean inclusive, @Param("limit") int limit);
}
