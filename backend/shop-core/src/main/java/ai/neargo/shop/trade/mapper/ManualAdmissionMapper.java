package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmissionAction;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Every original receipt lookup is scoped; absence is serialized by the existing order root. */
public interface ManualAdmissionMapper extends BaseMapper<OrdManualServiceAdmission> {
    @Select(
            "SELECT * FROM ord_manual_service_admission WHERE tenant_no='MAIN' AND"
                    + " entity_no=#{entity} AND store_no=#{store} AND order_no=#{order} AND deleted=0"
                    + " FOR UPDATE")
    OrdManualServiceAdmission lockByOrder(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("order") String order);

    @Select(
            "SELECT * FROM ord_manual_service_admission WHERE tenant_no='MAIN' AND"
                    + " entity_no=#{entity} AND store_no=#{store} AND operation_no=#{operation} AND"
                    + " request_key_hash=#{key} AND deleted=0")
    OrdManualServiceAdmission findKey(
            @Param("entity") String entity,
            @Param("store") String store,
            @Param("operation") String operation,
            @Param("key") String key);

    @Select(
            "SELECT * FROM ord_manual_service_admission WHERE tenant_no='MAIN' AND"
                    + " phase='STOCK_PENDING' AND (next_retry_at IS NULL OR"
                    + " next_retry_at<=CURRENT_TIMESTAMP(3)) AND deleted=0 ORDER BY next_retry_at,id"
                    + " LIMIT #{limit}")
    List<OrdManualServiceAdmission> pending(@Param("limit") int limit);

    @Select(
            "SELECT * FROM ord_order WHERE tenant_no='MAIN' AND order_no=#{order} AND deleted=0 FOR"
                    + " UPDATE")
    OrdOrder lockParent(@Param("order") String order);

    @Select(
            "SELECT * FROM ord_sub_order WHERE tenant_no='MAIN' AND order_no=#{order} AND deleted=0"
                    + " ORDER BY sub_order_no FOR UPDATE")
    List<OrdSubOrder> lockChildren(@Param("order") String order);

    @Select(
            "SELECT * FROM ord_manual_service_admission WHERE tenant_no='MAIN' AND caller=#{caller}"
                    + " AND operation_no=#{operation} AND entity_no=#{entity} AND store_no=#{store} AND"
                    + " deleted=0")
    OrdManualServiceAdmission snapshot(
            @Param("caller") String caller,
            @Param("operation") String operation,
            @Param("entity") String entity,
            @Param("store") String store);

    @Select(
            "SELECT (SELECT COUNT(*) FROM ord_checkout_payment_request WHERE tenant_no='MAIN' AND"
                    + " order_no=#{order} AND deleted=0)+(SELECT COUNT(*) FROM ord_payment_resolution"
                    + " WHERE tenant_no='MAIN' AND pay_no=#{order} AND deleted=0)")
    int financialConflicts(@Param("order") String order);

    @Select(
            "SELECT (SELECT COUNT(*) FROM ord_after_sale WHERE tenant_no='MAIN' AND"
                    + " sub_order_no=#{sub} AND deleted=0)+(SELECT COUNT(*) FROM ord_sub_order WHERE"
                    + " tenant_no='MAIN' AND sub_order_no=#{sub} AND open_after_sale_no IS NOT NULL AND"
                    + " deleted=0)")
    int afterSaleConflicts(@Param("sub") String sub);

    /**
     * The exact durable manual declaration, owner mode and expected root version are the transition
     * authority.
     */
    @Update(
            "UPDATE ord_order o SET"
                    + " status='PAID',paid_at=#{received},receipt_provenance='MANUAL_DECLARATION',"
                    + " offline_confirmed_by=#{actor},offline_confirmed_at=#{recorded},"
                    + "updated_at=CURRENT_TIMESTAMP(3),version=version+1"
                    + " WHERE o.tenant_no='MAIN' AND o.order_no=#{order} AND o.deleted=0 AND"
                    + " o.version=#{revision} AND o.status='WAIT_OFFLINE_PAY' AND"
                    + " o.admission_kind='MANUAL_OFFLINE' AND o.pay_channel IS NULL AND o.pay_trade_no"
                    + " IS NULL AND o.points_integrated=0 AND EXISTS (SELECT 1 FROM"
                    + " ord_manual_service_admission d WHERE d.tenant_no=o.tenant_no AND"
                    + " d.order_no=o.order_no AND d.declaration_no=#{declaration} AND"
                    + " d.payload_hash=#{payload} AND d.admission_kind='MANUAL_OFFLINE' AND"
                    + " d.phase='STOCK_PENDING' AND d.order_revision=#{revision} AND"
                    + " d.declared_minor=o.pay_amount AND d.currency=o.currency AND d.deleted=0) AND"
                    + " NOT EXISTS (SELECT 1 FROM ord_checkout_payment_request p WHERE"
                    + " p.tenant_no=o.tenant_no AND p.order_no=o.order_no AND p.deleted=0) AND NOT"
                    + " EXISTS (SELECT 1 FROM ord_payment_resolution p WHERE p.tenant_no=o.tenant_no"
                    + " AND p.pay_no=o.order_no AND p.deleted=0)")
    int confirmParent(
            @Param("order") String order,
            @Param("revision") long revision,
            @Param("declaration") String declaration,
            @Param("payload") String payload,
            @Param("received") long received,
            @Param("recorded") long recorded,
            @Param("actor") String actor);

    @Update(
            "UPDATE ord_sub_order s SET"
                    + " status='FULFILLING',updated_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE"
                    + " s.tenant_no='MAIN' AND s.sub_order_no=#{sub} AND s.order_no=#{order} AND"
                    + " s.deleted=0 AND s.version=#{revision} AND s.status='WAIT_PAY' AND"
                    + " s.admission_kind='MANUAL_OFFLINE' AND"
                    + " s.fulfillment_authority='SERVICE_EXECUTION' AND s.fulfillment='STORE_VERIFY'"
                    + " AND s.source_operation_no=#{operation} AND s.open_after_sale_no IS NULL AND"
                    + " s.group_no IS NULL AND s.period_no IS NULL AND s.points_deduct=0 AND"
                    + " s.points_deduct_minor=0 AND EXISTS (SELECT 1 FROM ord_manual_service_admission"
                    + " d WHERE d.tenant_no=s.tenant_no AND d.order_no=s.order_no AND"
                    + " d.sub_order_no=s.sub_order_no AND d.declaration_no=#{declaration} AND"
                    + " d.payload_hash=#{payload} AND d.phase='STOCK_PENDING' AND"
                    + " d.admission_kind='MANUAL_OFFLINE' AND d.deleted=0)")
    int confirmChild(
            @Param("order") String order,
            @Param("sub") String sub,
            @Param("revision") long revision,
            @Param("operation") String operation,
            @Param("declaration") String declaration,
            @Param("payload") String payload);

    /** A stock observation cannot cross the receipt/order version inspected before owner IO. */
    @Update(
            "UPDATE ord_manual_service_admission d SET"
                    + " phase=#{row.phase},admission_revision=#{row.admissionRevision},"
                    + " stock_outcome=#{row.stockOutcome},stock_state=#{row.stockState},"
                    + "stock_observation_hash=#{row.stockObservationHash},"
                    + " reason_code=#{row.reasonCode},next_retry_at=#{row.nextRetryAt},"
                    + "updated_at=#{row.updatedAt},version=version+1"
                    + " WHERE d.id=#{row.id} AND d.tenant_no='MAIN' AND d.version=#{expected} AND"
                    + " d.order_revision=#{row.orderRevision} AND d.declaration_no=#{row.declarationNo}"
                    + " AND d.payload_hash=#{row.payloadHash} AND d.deleted=0 AND EXISTS (SELECT 1 FROM"
                    + " ord_order o WHERE o.tenant_no=d.tenant_no AND o.order_no=d.order_no AND"
                    + " o.version=d.order_revision AND o.status='PAID' AND"
                    + " o.admission_kind='MANUAL_OFFLINE' AND o.receipt_provenance='MANUAL_DECLARATION'"
                    + " AND o.deleted=0)")
    int advanceAdmission(
            @Param("row") OrdManualServiceAdmission row, @Param("expected") long expected);

    /** Append-only action evidence has no general update API. */
    interface ActionMapper extends BaseMapper<OrdManualServiceAdmissionAction> {}
}
