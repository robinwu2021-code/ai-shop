package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutPaymentRequest;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Queries always name the tenant and immutable owner key; no repository wrapper. */
public final class CheckoutMappers {
    private CheckoutMappers() {}

    /**
     * Durable payment requests are looked up only through their full actor namespace or business
     * identity.
     */
    public interface PaymentRequestMapper extends BaseMapper<OrdCheckoutPaymentRequest> {
        @Select(
                "SELECT * FROM ord_checkout_payment_request WHERE tenant_no='MAIN' AND"
                        + " actor_realm=#{realm} AND actor_kind=#{kind} AND actor_no=#{actor} AND"
                        + " action=#{action} AND request_key_hash=#{key} AND deleted=0")
        OrdCheckoutPaymentRequest findKey(
                @Param("realm") String realm,
                @Param("kind") String kind,
                @Param("actor") String actor,
                @Param("action") String action,
                @Param("key") String key);

        @Select(
                "SELECT * FROM ord_checkout_payment_request WHERE tenant_no='MAIN'"
                        + " AND payment_operation_no=#{no} AND deleted=0")
        OrdCheckoutPaymentRequest find(@Param("no") String no);

        @Select(
                "SELECT * FROM ord_checkout_payment_request WHERE tenant_no='MAIN' AND"
                        + " caller=#{caller} AND operation_no=#{no} AND deleted=0 ORDER BY id")
        List<OrdCheckoutPaymentRequest> forOperation(
                @Param("caller") String caller, @Param("no") String no);
    }

    /** Serialize each stable caller operation before any lifecycle or source observation change. */
    public interface OperationMapper extends BaseMapper<OrdCheckoutOperation> {
        @Select(
                "SELECT * FROM ord_checkout_operation WHERE tenant_no='MAIN' AND caller=#{caller}"
                    + " AND entity_no=#{entity} AND store_no=#{store} AND request_key_hash=#{key}"
                    + " AND deleted=0")
        OrdCheckoutOperation findPrepareKey(
                @Param("caller") String caller,
                @Param("entity") String entity,
                @Param("store") String store,
                @Param("key") String key);

        @Select(
                "SELECT * FROM ord_checkout_operation WHERE tenant_no='MAIN' AND caller=#{caller} "
                        + "AND operation_no=#{operation} AND buyer_no=#{buyer} AND deleted=0")
        OrdCheckoutOperation findForBuyer(
                @Param("caller") String caller,
                @Param("operation") String operation,
                @Param("buyer") String buyer);

        @Select(
                "SELECT * FROM ord_checkout_operation WHERE tenant_no='MAIN' AND caller=#{caller} "
                        + "AND operation_no=#{operation} AND deleted=0")
        OrdCheckoutOperation find(
                @Param("caller") String caller, @Param("operation") String operation);

        @Select(
                "SELECT * FROM ord_checkout_operation WHERE tenant_no='MAIN' AND caller=#{caller} "
                        + "AND operation_no=#{operation} AND deleted=0 FOR UPDATE")
        OrdCheckoutOperation lock(
                @Param("caller") String caller, @Param("operation") String operation);
    }

    /** Lock immutable economic evidence together with the current operation. */
    public interface QuoteMapper extends BaseMapper<OrdCheckoutQuote> {
        @Select(
                "SELECT * FROM ord_checkout_quote WHERE tenant_no='MAIN' AND quote_no=#{no} AND"
                        + " deleted=0")
        OrdCheckoutQuote find(@Param("no") String no);

        @Select(
                "SELECT * FROM ord_checkout_quote WHERE tenant_no='MAIN' AND quote_no=#{no} AND"
                        + " deleted=0 FOR UPDATE")
        OrdCheckoutQuote lock(@Param("no") String no);
    }

    /** Enforce permanent consent keys and one consumption alongside the ordinary order write. */
    public interface ConfirmationMapper extends BaseMapper<OrdCheckoutConfirmation> {
        @Select(
                "SELECT * FROM ord_checkout_confirmation WHERE tenant_no='MAIN' "
                        + "AND confirmation_no=#{no} AND deleted=0 FOR UPDATE")
        OrdCheckoutConfirmation lock(@Param("no") String no);
    }

    /** Preserve real pending refund states even when an older writer does not increment version. */
    public record RefundWork(
            String kind, String referenceNo, String status, String resourceStatus) {}

    /**
     * Read and write immutable original item links; refund work remains a plain consistent read.
     */
    public interface LineMapper extends BaseMapper<OrdCheckoutLine> {
        @Select(
                "SELECT 'CASH' AS kind,execution_no AS referenceNo,status,resource_status AS"
                    + " resourceStatus FROM ord_refund_execution WHERE tenant_no='MAIN' AND"
                    + " sub_order_no=#{no} AND deleted=0 UNION ALL SELECT"
                    + " 'POINTS',after_sale_no,state,NULL FROM ord_points_refund_execution WHERE"
                    + " tenant_no='MAIN' AND sub_order_no=#{no} AND deleted=0 ORDER BY"
                    + " kind,referenceNo")
        List<RefundWork> refundWork(@Param("no") String subOrderNo);
    }
}
