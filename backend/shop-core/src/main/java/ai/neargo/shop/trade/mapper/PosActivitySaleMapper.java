package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.common.config.TradeSqlMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Source facts scoped to a POS store. Refund joins only consume successful, dated original-line records. */
@Mapper
public interface PosActivitySaleMapper extends TradeSqlMapper {
    @Insert("""
        INSERT INTO ord_pos_activity_line(tenant_no,sub_order_no,item_id,apply_no,activity_no,activity_version_no,discount_minor)
        VALUES (#{tenant},#{sub},#{item},#{use},#{activity},#{version},#{discount})
        """)
    int insert(String tenant,String sub,long item,String use,String activity,String version,long discount);

    record Facts(Integer schemaVersion, String memberNo) {}

    @Update("""
        UPDATE ord_order SET pos_activity_facts_version=#{schemaVersion},pos_member_no=#{memberNo}
        WHERE tenant_no=#{tenant} AND order_no=#{orderNo} AND admission_kind='STORE_SALE' AND deleted=0
        """)
    int setLegacyFacts(String tenant, String orderNo, int schemaVersion, String memberNo);

    @Select("""
        SELECT pos_activity_facts_version schema_version,pos_member_no member_no FROM ord_order
        WHERE tenant_no=#{tenant} AND order_no=#{orderNo} AND admission_kind='STORE_SALE' AND deleted=0
        AND pos_activity_facts_version IS NOT NULL
        """)
    Facts legacyFacts(String tenant, String orderNo);

    record Quote(String tenant, String quoteNo, String orderNo, String subNo, String entity, String store,
                 String userNo, String memberNo, int schemaVersion, String currency, long goods, long activity,
                 long manual, long receivable) {}
    record QuoteLine(String tenant, String quoteNo, String quoteLineNo, String sourceLineNo, String pricingUnitRef,
                     int sort, String goodsNo, String skuNo, String title, String taxClass, int qty, long unitPrice,
                     long goods, long activity, long paid) {}
    record Allocation(String tenant, String allocationNo, String quoteNo, String quoteLineNo, String orderNo,
                      String subNo, int step, String applyNo, String activityNo, String version, String originalVersion,
                      long basis, long discount, String currency, String userNo) {}

    @Insert("""
        INSERT INTO ord_price_quote(tenant_no,quote_no,entity_no,store_no,subject_no,industry_code,source_system,
          source_type,source_no,channel,quote_phase,currency,engine_spec_version,facts_snapshot,schema_version,
          goods_minor,activity_discount_minor,manual_discount_minor,receivable_minor,remaining_due_minor,status,retained_ref)
        VALUES (#{tenant},#{quoteNo},#{entity},#{store},#{userNo},'RETAIL','SELF','SUB_ORDER',#{subNo},'POS',
          'CHECKOUT',#{currency},'POS_SALE_FACTS',JSON_OBJECT('memberNo',#{memberNo},'taxCalculated',FALSE),
          #{schemaVersion},#{goods},#{activity},#{manual},#{receivable},#{receivable},'CONSUMED',#{orderNo})
        """)
    int insertQuote(Quote quote);

    @Update("""
        UPDATE ord_sub_order SET quote_no=#{quoteNo}
        WHERE tenant_no=#{tenant} AND sub_order_no=#{subNo} AND order_no=#{orderNo}
        AND admission_kind='STORE_SALE' AND quote_no IS NULL AND deleted=0
        """)
    int linkQuote(String tenant, String subNo, String orderNo, String quoteNo);

    @Update("""
        UPDATE ord_item SET line_no=#{lineNo}
        WHERE tenant_no=#{tenant} AND sub_order_no=#{subNo} AND id=#{itemId} AND line_no IS NULL AND deleted=0
        """)
    int linkLine(String tenant, String subNo, long itemId, String lineNo);

    @Insert("""
        INSERT INTO ord_price_quote_line(tenant_no,quote_no,quote_line_no,source_line_no,pricing_unit_ref,
          invoice_line_ref,goods_no,sku_no,line_kind,quantity_start,quantity_end,quantity,unit_price_minor,goods_minor,
          price_adjustment_minor,activity_discount_minor,coupon_discount_minor,manual_discount_minor,
          points_discount_minor,waiver_minor,fee_minor,net_minor,tax_minor,receivable_minor,consideration_offset_minor,
          invoice_base_minor,tax_class,eligibility_snapshot,source_snapshot,sort_order)
        VALUES (#{tenant},#{quoteNo},#{quoteLineNo},#{sourceLineNo},#{pricingUnitRef},#{sourceLineNo},
          #{goodsNo},#{skuNo},'GOODS',0,#{qty},#{qty},#{unitPrice},#{goods},0,#{activity},0,#{goods}-#{activity}-#{paid},
          0,0,0,0,0,#{paid},0,0,COALESCE(#{taxClass},'UNKNOWN'),
          JSON_OBJECT('activityDiscountMinor',#{activity}),JSON_OBJECT('title',#{title},'taxCalculated',FALSE),#{sort})
        """)
    int insertQuoteLine(QuoteLine line);

    @Insert("""
        INSERT INTO ord_price_quote_allocation(tenant_no,allocation_no,quote_no,quote_line_no,allocation_kind,stage,
          step_order,benefit_ref,owner,application_ref,rule_version,basis_minor,amount_minor,evidence_snapshot,
          order_no,sub_order_no,currency,status,user_no)
        VALUES (#{tenant},#{allocationNo},#{quoteNo},#{quoteLineNo},'ACTIVITY','DISCOUNT',#{step},#{activityNo},'MERCHANT',
          #{applyNo},#{version},#{basis},#{discount},JSON_OBJECT('activityVersion',#{originalVersion}),
          #{orderNo},#{subNo},#{currency},'APPLIED',#{userNo})
        """)
    int insertAllocation(Allocation allocation);

    String POS_QUOTE_JOIN = """
        q.tenant_no=s.tenant_no AND q.quote_no=s.quote_no AND q.entity_no=s.entity_no AND q.store_no=s.store_no
        AND q.source_type='SUB_ORDER' AND q.source_no=s.sub_order_no AND q.retained_ref=s.order_no
        AND q.engine_spec_version='POS_SALE_FACTS' AND q.deleted=0
        """;

    @Select("""
        SELECT q.schema_version,JSON_VALUE(q.facts_snapshot,'$.memberNo') member_no
        FROM ord_sub_order s JOIN ord_price_quote q ON
        """ + POS_QUOTE_JOIN + """
        WHERE s.tenant_no=#{tenant} AND s.sub_order_no=#{subNo} AND s.order_no=#{orderNo}
        AND s.admission_kind='STORE_SALE' AND s.deleted=0
        """)
    Facts canonicalFacts(String tenant, String subNo, String orderNo);

    @Update("""
        UPDATE ord_price_quote SET facts_snapshot=JSON_SET(facts_snapshot,'$.memberAtPayment',CASE WHEN #{active} THEN 1 ELSE 0 END),
          version=version+1
        WHERE tenant_no=#{tenant} AND source_type='SUB_ORDER' AND source_no=#{subNo} AND retained_ref=#{orderNo}
        AND engine_spec_version='POS_SALE_FACTS' AND deleted=0
        """)
    int recordCanonicalMembership(String tenant, String subNo, String orderNo, boolean active);

    @Update("""
        UPDATE ord_price_quote_allocation SET status=#{status},decided_at=CURRENT_TIMESTAMP(3),version=version+1
        WHERE tenant_no=#{tenant} AND sub_order_no=#{subNo} AND order_no=#{orderNo}
        AND allocation_kind='ACTIVITY' AND status='APPLIED' AND deleted=0
        """)
    int decideAllocations(String tenant, String subNo, String orderNo, String status);

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


    String SCOPED_SALE = """
        FROM ord_order o
        JOIN ord_sub_order s ON s.tenant_no=o.tenant_no AND s.order_no=o.order_no
          AND s.entity_no=o.entity_no AND s.store_no=o.store_no AND s.deleted=0
        LEFT JOIN ord_price_quote q ON
        """ + POS_QUOTE_JOIN;

    String SCOPED_FILTER = """
        WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.store_no=#{store}
        AND o.admission_kind='STORE_SALE' AND s.admission_kind='STORE_SALE'
        AND o.deleted=0 AND o.paid_at < #{end}
        """;

    String MEMBERSHIP = "JSON_VALUE(q.facts_snapshot,'$.memberAtPayment')";

    @Select("SELECT o.currency,MIN(CASE WHEN " + MEMBERSHIP + " IS NOT NULL THEN o.paid_at END) first_paid "
        + SCOPED_SALE + SCOPED_FILTER + " GROUP BY o.currency")
    List<Coverage> canonicalCoverage(String tenant, String entity, String store, long end);

    String SELECTED_ACTIVITY = """
        a.tenant_no=q.tenant_no AND a.quote_no=q.quote_no AND a.order_no=o.order_no AND a.sub_order_no=s.sub_order_no
        AND a.allocation_kind='ACTIVITY' AND a.status='COMMITTED' AND a.benefit_ref=#{activity}
        AND a.deleted=0
        """;

    String CASH_RETURN = """
        FROM ord_payment r JOIN ord_payment p ON p.tenant_no=r.tenant_no AND p.record_no=r.corrects_record_no
          AND p.entity_no=r.entity_no AND p.store_no=r.store_no AND p.sub_order_no=r.sub_order_no AND p.deleted=0
        WHERE r.tenant_no=o.tenant_no AND r.entity_no=o.entity_no AND r.store_no=o.store_no
          AND r.sub_order_no=s.sub_order_no AND r.currency=o.currency AND r.source='OFFLINE' AND r.method='CASH'
          AND p.currency=r.currency AND p.source='OFFLINE' AND p.method='CASH' AND p.status='REVERSED'
          AND p.amount_minor=o.pay_amount AND r.amount_minor=-p.amount_minor AND r.status='CONFIRMED' AND r.deleted=0
        """;

    @Select("""
        SELECT s.sub_order_no sub_no,o.currency,o.paid_at,
          CASE
        """ + MEMBERSHIP + """
          WHEN '1' THEN TRUE WHEN '0' THEN FALSE END member_at_payment,
          i.qty,i.paid_share_minor paid,COALESCE((
            SELECT SUM(a.amount_minor) FROM ord_price_quote_allocation a
            JOIN ord_price_quote_line l ON l.tenant_no=a.tenant_no AND l.quote_no=a.quote_no
              AND l.quote_line_no=a.quote_line_no AND l.source_line_no=i.line_no AND l.deleted=0
            WHERE
        """ + SELECTED_ACTIVITY + """
          ),0) discount,
          COALESCE((SELECT SUM(r.qty) FROM ord_after_sale_item r JOIN ord_after_sale a
            ON a.tenant_no=r.tenant_no AND a.after_sale_no=r.after_sale_no AND a.sub_order_no=r.sub_order_no
            WHERE r.tenant_no=o.tenant_no AND r.sub_order_no=s.sub_order_no AND r.item_id=i.id AND r.deleted=0
            AND a.deleted=0 AND a.refunded_at < #{end} AND a.status='REFUNDED'
            AND COALESCE(a.refund_mode,'CASH')='CASH'),0) refund_qty,
          COALESCE((SELECT SUM(r.refund_minor) FROM ord_after_sale_item r JOIN ord_after_sale a
            ON a.tenant_no=r.tenant_no AND a.after_sale_no=r.after_sale_no AND a.sub_order_no=r.sub_order_no
            WHERE r.tenant_no=o.tenant_no AND r.sub_order_no=s.sub_order_no AND r.item_id=i.id AND r.deleted=0
            AND a.deleted=0 AND a.refunded_at < #{end} AND a.status='REFUNDED'
            AND COALESCE(a.refund_mode,'CASH')='CASH'),0) refund_minor,
          EXISTS(SELECT 1
        """ + CASH_RETURN + """
            AND r.received_at < #{endUtc}) cash_returned,
          (EXISTS(SELECT 1 FROM ord_after_sale a WHERE a.tenant_no=o.tenant_no AND a.sub_order_no=s.sub_order_no
            AND a.deleted=0 AND a.status='REFUNDED' AND COALESCE(a.refund_mode,'CASH')='CASH'
            AND (a.refunded_at IS NULL OR (a.refunded_at < #{end} AND NOT EXISTS (
              SELECT 1 FROM ord_after_sale_item r WHERE r.tenant_no=a.tenant_no AND r.after_sale_no=a.after_sale_no
              AND r.sub_order_no=a.sub_order_no AND r.deleted=0))))
          OR (s.refund_progress='FULL' AND NOT EXISTS(SELECT 1
        """ + CASH_RETURN + """
            AND r.received_at IS NOT NULL) AND NOT EXISTS(
              SELECT 1 FROM ord_after_sale a WHERE a.tenant_no=o.tenant_no AND a.sub_order_no=s.sub_order_no
              AND a.deleted=0 AND a.status='REFUNDED'))) missing_return
        """ + SCOPED_SALE + """
        JOIN ord_item i ON i.tenant_no=s.tenant_no AND i.sub_order_no=s.sub_order_no AND i.deleted=0
        """ + SCOPED_FILTER + """
        AND o.paid_at >= #{start} AND (
        """ + MEMBERSHIP + """
          IS NULL OR EXISTS(SELECT 1 FROM ord_price_quote_allocation a WHERE
        """ + SELECTED_ACTIVITY + """
          ))
        ORDER BY o.paid_at,s.sub_order_no,i.id
        """)
    List<Line> canonicalLines(String tenant, String entity, String store, String activity, long start, long end,
                              LocalDateTime endUtc);
}
