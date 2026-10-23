package ai.neargo.shop.beauty.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Uses current B grants and the original USER or immutable guest claim receipt; never impersonates
 * C.
 */
public final class ServiceSubmissionAccess {
    private final AppointmentAccess appointments;
    private final ReceptionIdentity identities;
    private final BeautyMappers.GuestClaimReceiptMapper receipts;
    private final ObjectProvider<MerchantOperatorAccessPort> operators;

    public ServiceSubmissionAccess(
            AppointmentAccess appointments,
            ReceptionIdentity identities,
            BeautyMappers.GuestClaimReceiptMapper receipts,
            ObjectProvider<MerchantOperatorAccessPort> operators) {
        this.appointments = appointments;
        this.identities = identities;
        this.receipts = receipts;
        this.operators = operators;
    }

    /** Authorization happens outside all local transactions and is repeated on every replay. */
    public LoginUser authorize(
            String entity, String store, String actor, boolean write, boolean prepare) {
        appointments.merchant(entity, store, actor, write);
        identities.authorize(entity, store, actor);
        var user = appointments.current(actor);
        requireGrant(user, entity, store, BizPerms.ORDER_VIEW);
        if (prepare) {
            requireGrant(user, entity, store, BizPerms.CHECKOUT_PREPARE);
        }
        return user;
    }

    /**
     * The short reference is checked transiently; only the trusted result/receipt becomes durable.
     */
    public Buyer buyer(BtyWorkOrderContext context, String customerRef) {
        String user;
        String receipt = null;
        Long revision = null;
        if ("USER".equals(context.getIdentityKind())) {
            user = context.getIdentityRef();
        } else {
            var verified =
                    identities.guest(
                            context.getEntityNo(), context.getStoreNo(), context.getIdentityRef());
            if (!"USER".equals(verified.kind())) {
                throw BizException.of(ErrorCode.BEAUTY_SOURCE_CUSTOMER_REQUIRED);
            }
            user = verified.reference();
            var row =
                    DataScopeContext.executeWithoutScope(
                            () ->
                                    receipts.selectOne(
                                            Wrappers.<BtyGuestClaimReceipt>lambdaQuery()
                                                    .eq(BtyGuestClaimReceipt::getTenantNo, "MAIN")
                                                    .eq(
                                                            BtyGuestClaimReceipt::getEntityNo,
                                                            context.getEntityNo())
                                                    .eq(
                                                            BtyGuestClaimReceipt::getStoreNo,
                                                            context.getStoreNo())
                                                    .eq(
                                                            BtyGuestClaimReceipt::getGuestNo,
                                                            context.getIdentityRef())
                                                    .eq(
                                                            BtyGuestClaimReceipt::getUserNo,
                                                            verified.reference())));
            if (row == null || row.getClaimRevision() != 2 || row.getOwnerReceiptHash() == null) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            receipt = row.getOwnerReceiptHash();
            revision = row.getClaimRevision();
        }
        if (customerRef != null
                && !user.equals(
                        identities.customer(
                                context.getEntityNo(), context.getStoreNo(), customerRef))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new Buyer(user, receipt, revision);
    }

    public record Buyer(String userNo, String receiptHash, Long claimRevision) {}

    private void requireGrant(LoginUser user, String entity, String store, String permission) {
        var owner = operators.getIfAvailable();
        if (owner == null
                || !owner.allowed(
                        new MerchantOperatorAccessPort.Query(
                                "MAIN",
                                entity,
                                store,
                                user.userNo(),
                                user.realm().name(),
                                user.subjectKind().name(),
                                permission))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
}
