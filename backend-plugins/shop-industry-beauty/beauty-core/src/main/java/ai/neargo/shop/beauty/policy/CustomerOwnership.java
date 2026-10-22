package ai.neargo.shop.beauty.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** Receipt ownership is established by an earlier explicit verification, never a live guess by guest number. */
public final class CustomerOwnership {
    private final BeautyMappers.GuestClaimReceiptMapper receipts;
    public CustomerOwnership(BeautyMappers.GuestClaimReceiptMapper receipts) { this.receipts = receipts; }
    public boolean owns(BtyAppointment root, String user) {
        if (root == null || !"MAIN".equals(root.getTenantNo()) || !Integer.valueOf(0).equals(root.getDeleted())) {
            return false;
        }
        if (root.getCustomerRef() != null) { return user.equals(root.getCustomerRef()); }
        if (root.getGuestRef() == null) { return false; }
        return DataScopeContext.executeWithoutScope(() -> receipts.selectCount(
                Wrappers.<BtyGuestClaimReceipt>lambdaQuery().eq(BtyGuestClaimReceipt::getTenantNo, "MAIN")
                        .eq(BtyGuestClaimReceipt::getEntityNo, root.getEntityNo())
                        .eq(BtyGuestClaimReceipt::getStoreNo, root.getStoreNo())
                        .eq(BtyGuestClaimReceipt::getGuestNo, root.getGuestRef())
                        .eq(BtyGuestClaimReceipt::getUserNo, user).eq(BtyGuestClaimReceipt::getDeleted, 0))) > 0;
    }
    /** Use this predicate inside both page SELECT and count, before LIMIT. */
    public static String appointmentPredicate() {
        return "(bty_appointment.customer_ref={0} OR (bty_appointment.customer_ref IS NULL AND EXISTS "
                + "(SELECT 1 FROM bty_guest_claim_receipt r WHERE r.tenant_no=bty_appointment.tenant_no "
                + "AND r.entity_no=bty_appointment.entity_no AND r.store_no=bty_appointment.store_no "
                + "AND r.guest_no=bty_appointment.guest_ref AND r.user_no={0} AND r.deleted=0)))";
    }
}
