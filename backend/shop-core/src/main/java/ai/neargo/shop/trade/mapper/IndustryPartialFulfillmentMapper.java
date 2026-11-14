package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface IndustryPartialFulfillmentMapper {
    @Select(
            "SELECT * FROM ord_industry_fulfillment WHERE status IN ('PREPARED','UNKNOWN') AND"
                    + " command_snapshot IS NOT NULL AND deleted=0 ORDER BY id LIMIT #{limit}")
    List<OrdIndustryFulfillment> pending(@Param("limit") int limit);

    @Select(
            "SELECT COALESCE(SUM(l.physical_reduction_qty),0) FROM ord_industry_amendment_line l"
                + " JOIN ord_industry_amendment a ON a.amendment_no=l.amendment_no WHERE"
                + " a.tenant_no=#{tenant} AND a.entity_no=#{entity} AND a.sub_order_no=#{sub} AND"
                + " a.status='CONFIRMED' AND l.order_item_id=#{item}")
    BigDecimal physicalReduction(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("sub") String sub,
            @Param("item") Long item);

    @Select(
            "SELECT COUNT(*) FROM ord_industry_amendment WHERE tenant_no=#{tenant} AND"
                    + " entity_no=#{entity} AND sub_order_no=#{sub} AND status IN"
                    + " ('PREPARED','UNKNOWN')")
    long pendingAmendments(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("sub") String sub);

    @Select(
            "SELECT fulfilled_qty FROM ord_industry_fulfillment_progress WHERE tenant_no=#{tenant}"
                + " AND entity_no=#{entity} AND sub_order_no=#{sub} AND order_item_id=#{item} AND"
                + " source_component_ref=#{component}")
    BigDecimal quantity(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("sub") String sub,
            @Param("item") Long item,
            @Param("component") String component);

    @Insert(
            "INSERT INTO"
                + " ord_industry_fulfillment_progress(tenant_no,entity_no,sub_order_no,order_item_id,source_component_ref,fulfilled_qty,unit,plan_hash,created_by)"
                + " VALUES(#{tenant},#{entity},#{sub},#{item},#{component},#{qty},#{unit},#{hash},#{actor})"
                + " ON DUPLICATE KEY UPDATE"
                + " fulfilled_qty=VALUES(fulfilled_qty),updated_by=VALUES(created_by),version=version+1")
    int advance(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("sub") String sub,
            @Param("item") Long item,
            @Param("component") String component,
            @Param("qty") BigDecimal qty,
            @Param("unit") String unit,
            @Param("hash") String hash,
            @Param("actor") String actor);
}
