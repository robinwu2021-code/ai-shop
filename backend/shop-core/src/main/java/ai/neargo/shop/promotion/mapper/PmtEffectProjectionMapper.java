package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtEffectProjection;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Immutable source facts deduplicate by source event, activity and effect kind. */
public interface PmtEffectProjectionMapper extends BaseMapper<PmtEffectProjection> {
    @Insert("INSERT INTO pmt_effect_projection (tenant_no,source_event_no,activity_no,entity_no,store_no,"
            + "effect_kind,amount_minor,currency,units,occurred_at,version,deleted,created_at,updated_at,created_by,"
            + "updated_by) VALUES (#{tenantNo},#{sourceEventNo},#{activityNo},#{entityNo},#{storeNo},#{effectKind},"
            + "#{amountMinor},#{currency},#{units},#{occurredAt},0,0,#{createdAt},#{updatedAt},'SYSTEM','SYSTEM') "
            + "ON DUPLICATE KEY UPDATE id=id")
    int claim(PmtEffectProjection row);

    @Select("SELECT * FROM pmt_effect_projection WHERE tenant_no=#{tenant} AND source_event_no=#{event} "
            + "AND activity_no=#{activity} AND effect_kind='ORDER_DISCOUNT' AND deleted=0 FOR UPDATE")
    PmtEffectProjection lock(@Param("tenant") String tenant, @Param("event") String event,
                            @Param("activity") String activity);

    @Select({"<script>", "SELECT e.currency AS currency,SUM(e.amount_minor) AS amount_minor,COUNT(*) AS events "
            + "FROM pmt_effect_projection e JOIN pmt_apply a ON a.tenant_no=e.tenant_no "
            + "AND a.apply_no=e.source_event_no AND a.promo_no=e.activity_no AND a.entity_no=e.entity_no "
            + "WHERE e.tenant_no=#{tenant} AND e.entity_no=#{entity} AND e.effect_kind='ORDER_DISCOUNT' "
            + "AND e.deleted=0 AND a.deleted=0 AND a.reverted_at IS NULL "
            + "AND e.occurred_at&gt;=#{from} AND e.occurred_at&lt;#{to}",
            "<if test='stores != null'> AND e.store_no IN "
            + "<foreach collection='stores' item='store' open='(' separator=',' close=')'>#{store}</foreach></if>",
            "GROUP BY e.currency ORDER BY e.currency", "</script>"})
    @ConstructorArgs({@Arg(column = "currency", javaType = String.class),
            @Arg(column = "amount_minor", javaType = BigDecimal.class),
            @Arg(column = "events", javaType = long.class)})
    List<EffectTotal> summary(@Param("tenant") String tenant, @Param("entity") String entity,
            @Param("stores") List<String> stores, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
    record EffectTotal(String currency, BigDecimal amountMinor, long events) { }
}
