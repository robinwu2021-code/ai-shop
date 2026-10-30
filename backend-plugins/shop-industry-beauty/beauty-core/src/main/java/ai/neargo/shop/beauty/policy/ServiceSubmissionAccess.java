package ai.neargo.shop.beauty.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

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

    /** Party selected from the real reception/guest owner before counter preparation intent. */
    public CustomerParty manualParty(BtyWorkOrderContext context) {
        if ("USER".equals(context.getIdentityKind())) {
            return new CustomerParty("USER", context.getIdentityRef(), null, (Long) null);
        }
        var fact =
                identities.guestFact(
                        context.getEntityNo(), context.getStoreNo(), context.getIdentityRef());
        return "CLAIMED".equals(fact.state())
                ? new CustomerParty("USER", fact.userNo(), null, (Long) null)
                : new CustomerParty("GUEST", null, fact.guestNo(), fact.revision());
    }

    /** A manual quote is checked as its own domain; it never becomes a funded quote. */
    public CustomerParty manualParty(BtyWorkOrderContext context, CounterCheckoutQuote quote) {
        if (quote == null
                || !context.getEntityNo().equals(quote.quote().entityNo())
                || !context.getStoreNo().equals(quote.quote().storeNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        manualBuyer(context, quote.party());
        return quote.party();
    }

    /** Source identity is meaningful independently of admission readiness. */
    public CustomerParty manualParty(BtyWorkOrderContext context, ManualSourceFacts facts) {
        if (facts == null
                || !"BEAUTY".equals(facts.operation().caller())
                || !context.getEntityNo().equals(facts.entityNo())
                || !context.getStoreNo().equals(facts.storeNo())
                || facts.orderNo() == null
                || facts.subOrderNo() == null
                || !("MERCHANT_RECORDED_IN_PERSON".equals(facts.purchaseAuthority())
                        || "CONSUMER_SESSION".equals(facts.purchaseAuthority())
                                && "USER".equals(facts.party().kind()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        manualBuyer(context, facts.party());
        return facts.party();
    }

    /** USER-after-claim requires both the current owner fact and its immutable local receipt. */
    public Buyer manualBuyer(BtyWorkOrderContext context, CustomerParty party) {
        if ("USER".equals(context.getIdentityKind())) {
            if (!"USER".equals(party.kind()) || !context.getIdentityRef().equals(party.userNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return new Buyer(party.userNo(), null, null);
        }
        if ("GUEST".equals(party.kind())) {
            if (!context.getIdentityRef().equals(party.guestNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            var fact =
                    identities.guestFact(
                            context.getEntityNo(), context.getStoreNo(), party.guestNo());
            // A quote issued before a later claim retains its exact original guest party.
            if (party.identityRevision() == null
                    || party.identityRevision() != 1
                    || fact.revision() < party.identityRevision()) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return new Buyer(null, null, null);
        }
        var relation = buyer(context, null);
        if (!Objects.equals(relation.userNo(), party.userNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return relation;
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
