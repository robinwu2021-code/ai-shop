package ai.neargo.shop.beauty.mapper;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** Same original-root visibility as WorkOrderMapper.selectScoped, with SQL count and pagination. */
public interface WorkOrderWorkbenchMapper extends BaseMapper<BtyWorkOrder> {
    String FILTER =
            """
FROM bty_work_order w WHERE w.tenant_no='MAIN' AND w.entity_no=#{entity}
AND w.store_no=#{store} AND w.deleted=0
AND (NOT EXISTS (SELECT 1 FROM bty_work_order_context any_context
    WHERE any_context.tenant_no=w.tenant_no AND any_context.work_order_no=w.work_order_no)
  OR EXISTS (SELECT 1 FROM bty_work_order_context c WHERE c.tenant_no=w.tenant_no
    AND c.entity_no=w.entity_no AND c.store_no=w.store_no AND c.work_order_no=w.work_order_no
    AND c.deleted=0 AND w.customer_ref=CONCAT(c.identity_kind,':',c.identity_ref)))
<if test="q.status != null">AND w.status=#{q.status}</if>
<if test="q.query != null and q.query != ''">AND (w.work_order_no=#{q.query} OR w.appointment_no=#{q.query})</if>
<if test="from != null">AND w.created_at &gt;= #{from}</if>
<if test="to != null">AND w.created_at &lt; #{to}</if>
""";

    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long count(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("q") WorkOrderQueryReq query,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Select(
            "<script>SELECT w.* "
                    + FILTER
                    + " ORDER BY w.created_at DESC,w.id DESC LIMIT #{limit} OFFSET"
                    + " #{offset}</script>")
    List<BtyWorkOrder> list(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("q") WorkOrderQueryReq query,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
