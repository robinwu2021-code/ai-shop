package ai.neargo.shop.beauty.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 工单上下文：开单时冻结的身份与服务范围。原来是独立表 {@code bty_work_order_context}，B-3 起并入
 * {@code bty_work_order} 的可空列（TDD-美业域迁移新库 §2.3）；这里只是从工单行取出的只读视图，不再单独持久化。
 */
@Getter
@Setter
public class BtyWorkOrderContext {
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String workOrderNo;
    private String identityKind;
    private String identityRef;
    private String sourceReceptionOperationNo;
    private Long appointmentArrangementRevision;
    private String frozenScope;
    /** 所在工单行的版本。 */
    private Long version;

    /** 工单行没有上下文（旧工单）或不存在时返回 null，与原来「查不到上下文行」一致。 */
    public static BtyWorkOrderContext of(BtyWorkOrder order) {
        if (order == null || order.getIdentityKind() == null) return null;
        var context = new BtyWorkOrderContext();
        context.setTenantNo(order.getTenantNo());
        context.setEntityNo(order.getEntityNo());
        context.setStoreNo(order.getStoreNo());
        context.setWorkOrderNo(order.getWorkOrderNo());
        context.setIdentityKind(order.getIdentityKind());
        context.setIdentityRef(order.getIdentityRef());
        context.setSourceReceptionOperationNo(order.getSourceReceptionOperationNo());
        context.setAppointmentArrangementRevision(order.getAppointmentArrangementRevision());
        context.setFrozenScope(order.getFrozenScope());
        context.setVersion(order.getVersion());
        return context;
    }
}
