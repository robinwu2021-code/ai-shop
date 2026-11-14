package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.entity.FnbOrderBatch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One canonical filter is shared by count/page; batched detail reads are bounded and use the same
 * read snapshot.
 */
public interface DiningBatchQueryMapper extends BaseMapper<FnbOrderBatch> {
    String ANY_MEMBERSHIP =
            "SELECT 1 FROM fnb_check_batch_membership am WHERE am.tenant_no=b.tenant_no AND"
                    + " am.entity_no=b.entity_no AND am.batch_no=b.batch_no AND am.deleted=0 AND"
                    + " am.detached_at IS NULL";
    String SAFE_CHECK =
            "c.tenant_no=b.tenant_no AND c.entity_no=b.entity_no AND c.store_no=b.store_no AND"
                    + " c.deleted=0";
    String SOURCE =
            " FROM fnb_order_batch b WHERE b.tenant_no='MAIN' AND b.entity_no=#{entity} "
                    + "AND b.store_no=#{store} AND b.deleted=0 ";
    String CURRENT_LINE_JOINS =
            " FROM fnb_order_line l LEFT JOIN fnb_check_line_membership lm ON"
                + " lm.tenant_no=l.tenant_no AND lm.entity_no=l.entity_no AND lm.deleted=0 AND"
                + " lm.active_line_no=l.food_line_no LEFT JOIN fnb_check_batch_membership bm ON"
                + " bm.tenant_no=l.tenant_no AND bm.entity_no=l.entity_no AND"
                + " bm.batch_no=l.batch_no AND bm.deleted=0 AND bm.detached_at IS NULL ";
    String FILTER =
            SOURCE
                    + "<if test='query.originalCheckNo != null'> AND"
                    + " b.check_no=#{query.originalCheckNo}</if><if test='query.status != null'>"
                    + " AND b.status=#{query.status}</if><if test='query.businessDate != null'> AND"
                    + " b.business_date=#{query.businessDate}</if><if test='query.orderSource !="
                    + " null'> AND b.source=#{query.orderSource}</if><if test='query.checkNo !="
                    + " null'> AND EXISTS (SELECT 1 "
                    + CURRENT_LINE_JOINS
                    + " JOIN fnb_check c ON c.check_no=COALESCE(lm.check_no,bm.check_no,b.check_no)"
                    + " AND "
                    + SAFE_CHECK
                    + " WHERE l.tenant_no=b.tenant_no AND l.entity_no=b.entity_no AND"
                    + " l.batch_no=b.batch_no AND l.deleted=0 AND"
                    + " COALESCE(lm.check_no,bm.check_no,b.check_no)=#{query.checkNo})</if>";
    String PAGE_NUMBERS =
            "<foreach collection='numbers' item='number' open='(' separator=',' close=')'>"
                    + "#{number}</foreach>";

    @Select("<script>SELECT COUNT(*)" + FILTER + "</script>")
    long count(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("query") ListDiningBatchesQuery query);

    @Select(
            "<script>SELECT b.batch_no,b.store_no,b.check_no AS original_check_no,b.status,b.source"
                + " AS order_source,b.business_date,b.fulfillment,b.pay_mode,b.quote_ref AS"
                + " quote_no,b.quote_hash,b.created_at,b.accepted_at,b.scheduled_at,b.version,CASE"
                + " WHEN EXISTS ("
                    + ANY_MEMBERSHIP
                    + ") OR EXISTS (SELECT 1 FROM fnb_check_line_membership lm WHERE"
                    + " lm.tenant_no=b.tenant_no AND lm.entity_no=b.entity_no AND lm.deleted=0 AND"
                    + " lm.batch_no=b.batch_no AND lm.active_line_no IS NOT NULL) THEN 1 ELSE 0 END"
                    + " AS membership_authoritative"
                    + FILTER
                    + " ORDER BY b.created_at DESC,b.batch_no DESC LIMIT #{size} OFFSET"
                    + " #{offset}</script>")
    List<SourceRow> page(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("query") ListDiningBatchesQuery query,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select(
            "<script>SELECT DISTINCT b.batch_no,COALESCE(lm.check_no,bm.check_no,b.check_no) AS"
                + " check_no FROM fnb_order_batch b JOIN fnb_order_line l ON"
                + " l.tenant_no=b.tenant_no AND l.entity_no=b.entity_no AND l.batch_no=b.batch_no"
                + " AND l.deleted=0 LEFT JOIN fnb_check_line_membership lm ON"
                + " lm.tenant_no=l.tenant_no AND lm.entity_no=l.entity_no AND lm.deleted=0 AND"
                + " lm.active_line_no=l.food_line_no LEFT JOIN fnb_check_batch_membership bm ON"
                + " bm.tenant_no=b.tenant_no AND bm.entity_no=b.entity_no AND"
                + " bm.batch_no=b.batch_no AND bm.deleted=0 AND bm.detached_at IS NULL JOIN"
                + " fnb_check c ON c.check_no=COALESCE(lm.check_no,bm.check_no,b.check_no) AND "
                    + SAFE_CHECK
                    + " WHERE b.tenant_no='MAIN' AND b.entity_no=#{entity} AND b.store_no=#{store}"
                    + " AND b.deleted=0 AND b.batch_no IN "
                    + PAGE_NUMBERS
                    + " ORDER BY batch_no,check_no LIMIT 10001</script>")
    List<MembershipRow> currentChecks(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("numbers") List<String> numbers);

    @Select(
            "<script>SELECT b.batch_no,k.binding_no,k.payment_root_no,k.store_order_no,k.status AS"
                + " binding_status FROM fnb_order_batch b JOIN fnb_core_order_binding k ON"
                + " k.tenant_no=b.tenant_no AND k.entity_no=b.entity_no AND k.batch_no=b.batch_no"
                + " AND k.deleted=0 WHERE b.tenant_no='MAIN' AND b.entity_no=#{entity} AND"
                + " b.store_no=#{store} AND b.deleted=0 AND b.batch_no IN "
                    + PAGE_NUMBERS
                    + " ORDER BY b.batch_no,k.binding_no LIMIT 10001</script>")
    List<BindingRow> bindings(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("numbers") List<String> numbers);

    record SourceRow(
            String batchNo,
            String storeNo,
            String originalCheckNo,
            String status,
            String orderSource,
            LocalDate businessDate,
            String fulfillment,
            String payMode,
            String quoteNo,
            String quoteHash,
            LocalDateTime createdAt,
            LocalDateTime acceptedAt,
            LocalDateTime scheduledAt,
            long version,
            boolean membershipAuthoritative) {}

    record MembershipRow(String batchNo, String checkNo) {}

    record BindingRow(
            String batchNo,
            String bindingNo,
            String paymentRootNo,
            String storeOrderNo,
            String bindingStatus) {}
}
