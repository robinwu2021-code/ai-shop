package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.common.config.TradeSqlMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/** Source facts scoped to a POS store. Refund joins only consume successful, dated original-line records. */
@org.apache.ibatis.annotations.Mapper
public interface PosActivitySaleMapper extends TradeSqlMapper {
    @Insert("""
        INSERT INTO ord_pos_activity_line(tenant_no,sub_order_no,item_id,apply_no,activity_no,activity_version_no,discount_minor)
        VALUES (#{tenant},#{sub},#{item},#{use},#{activity},#{version},#{discount})
        """)
    int insert(String tenant,String sub,long item,String use,String activity,String version,long discount);

    record Coverage(String currency, Long firstPaid) {}
    @Select("""
        SELECT currency,MIN(CASE WHEN pos_member_at_payment IS NOT NULL THEN paid_at END) first_paid
        FROM ord_order WHERE tenant_no=#{tenant} AND entity_no=#{entity} AND store_no=#{store}
        AND admission_kind='STORE_SALE' AND deleted=0 AND paid_at < #{end} GROUP BY currency
        """)
    List<Coverage> coverage(String tenant,String entity,String store,long end);

    record Line(String subNo,String currency,long paidAt,Boolean memberAtPayment,int qty,long paid,long discount,
                long refundQty,long refundMinor,boolean cashReturned,boolean missingReturn) {}
    @Select("""
        SELECT s.sub_order_no sub_no,o.currency,o.paid_at,o.pos_member_at_payment member_at_payment,
               i.qty,i.paid_share_minor paid,COALESCE(p.discount_minor,0) discount,
               COALESCE((SELECT SUM(r.qty) FROM ord_after_sale_item r JOIN ord_after_sale a
                   ON a.tenant_no=r.tenant_no AND a.after_sale_no=r.after_sale_no AND a.sub_order_no=r.sub_order_no
                   WHERE r.tenant_no=o.tenant_no AND r.sub_order_no=s.sub_order_no AND r.item_id=i.id AND r.deleted=0
                   AND a.deleted=0 AND a.refunded_at < #{end} AND a.status='REFUNDED' AND COALESCE(a.refund_mode,'CASH')='CASH'),0) refund_qty,
               COALESCE((SELECT SUM(r.refund_minor) FROM ord_after_sale_item r JOIN ord_after_sale a
                   ON a.tenant_no=r.tenant_no AND a.after_sale_no=r.after_sale_no AND a.sub_order_no=r.sub_order_no
                   WHERE r.tenant_no=o.tenant_no AND r.sub_order_no=s.sub_order_no AND r.item_id=i.id AND r.deleted=0
                   AND a.deleted=0 AND a.refunded_at < #{end} AND a.status='REFUNDED' AND COALESCE(a.refund_mode,'CASH')='CASH'),0) refund_minor,
               EXISTS(SELECT 1 FROM ord_offline_record c JOIN ord_offline_record_event e
                   ON e.tenant_no=c.tenant_no AND e.record_no=c.record_no AND e.action='REFUND'
                   WHERE c.tenant_no=o.tenant_no AND c.entity_no=o.entity_no AND c.store_no=o.store_no
                   AND c.order_no=s.sub_order_no AND c.status='REFUNDED' AND e.occurred_at < #{endUtc}) cash_returned,
               EXISTS(SELECT 1 FROM ord_after_sale a WHERE a.tenant_no=o.tenant_no AND a.sub_order_no=s.sub_order_no
                   AND a.deleted=0 AND a.status='REFUNDED' AND COALESCE(a.refund_mode,'CASH')='CASH'
                   AND (a.refunded_at IS NULL OR (a.refunded_at < #{end} AND NOT EXISTS (
                   SELECT 1 FROM ord_after_sale_item r WHERE r.tenant_no=a.tenant_no AND r.after_sale_no=a.after_sale_no
                   AND r.sub_order_no=a.sub_order_no AND r.deleted=0)))) missing_return
        FROM ord_order o JOIN ord_sub_order s ON s.tenant_no=o.tenant_no AND s.order_no=o.order_no AND s.deleted=0
        JOIN ord_item i ON i.tenant_no=o.tenant_no AND i.sub_order_no=s.sub_order_no AND i.deleted=0
        LEFT JOIN ord_pos_activity_line p ON p.tenant_no=o.tenant_no AND p.sub_order_no=s.sub_order_no
            AND p.item_id=i.id AND p.activity_no=#{activity}
        WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.store_no=#{store} AND o.admission_kind='STORE_SALE'
        AND o.deleted=0 AND o.paid_at >= #{start} AND o.paid_at < #{end}
        AND (o.pos_member_at_payment IS NULL OR EXISTS (
            SELECT 1 FROM ord_pos_activity_line hit WHERE hit.tenant_no=o.tenant_no
            AND hit.sub_order_no=s.sub_order_no AND hit.activity_no=#{activity}))
        ORDER BY o.paid_at,s.sub_order_no,i.id
        """)
    List<Line> lines(String tenant,String entity,String store,String activity,long start,long end,LocalDateTime endUtc);
}
