package ai.neargo.shop.trade.policy;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy.CashierActor;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.user.QualificationPort;

import org.springframework.stereotype.Component;

/** Reject unsupported economic choices before the shared pricing or stock owners have effects. */
@Component
public class ManualServicePurchasePolicy {
    private final QualificationPort qualifications;
    @Autowired private CheckoutAccessPolicy access;
    @Autowired private TokenStores tokens;
    @Autowired private MerchantOperatorAccessPort merchants;

    /** Read requires exact action proof or a genuine current session, without receipt permissions. */
    public void requireCounterReader(CheckoutScope scope, OperationRef operation) {
        if (scope == null || operation == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        requireCounterPreparer(scope, "COUNTER_READ", operation.operationNo(), operation);
    }

    /** Full scoped request binding is required before alternate remote cashier resolution. */
    public CashierActor requireCounterCashier(
            CheckoutScope scope, String action, String target, Object request) {
        if ("COUNTER_PREPARE".equals(action)) {
            return requireCounterPreparer(scope, action, target, request);
        }
        CashierActor actor;
        if (BoundActionContext.present()) {
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    ManualServiceAdmissionInternalApi.ENDPOINT,
                                    action,
                                    DelegatedIdentityContext.current(),
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    target),
                            new Scoped<>(scope, request)));
            var merchant = access.requireMerchant(scope, BizPerms.CASH_RECEIVE);
            var cash =
                    merchants
                            .resolveAuthorizedOperator(
                                    new MerchantOperatorAccessPort.Query(
                                            scope.tenantNo(),
                                            scope.entityNo(),
                                            scope.storeNo(),
                                            merchant.actorNo(),
                                            merchant.realm(),
                                            merchant.subjectKind(),
                                            BizPerms.CASH_RECEIVE))
                            .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
            actor =
                    new CashierActor(
                            merchant.tenantNo(),
                            merchant.entityNo(),
                            merchant.storeNo(),
                            merchant.actorNo(),
                            merchant.realm(),
                            merchant.subjectKind(),
                            cash.accountNo());
        } else {
            actor = access.requireCurrentCashier(scope);
        }
        access.requireMerchant(scope, BizPerms.CHECKOUT_PREPARE);
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        access.requireMerchant(scope, BizPerms.MANUAL_CONFIRM);
        var manual =
                merchants
                        .resolveAuthorizedOperator(
                                new MerchantOperatorAccessPort.Query(
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        actor.actorNo(),
                                        actor.realm(),
                                        actor.subjectKind(),
                                        BizPerms.MANUAL_CONFIRM))
                        .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        if (!actor.accountNo().equals(manual.accountNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return actor;
    }

    private CashierActor requireCounterPreparer(
            CheckoutScope scope, String action, String target, Object request) {
        if (BoundActionContext.present()) {
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    ManualServiceAdmissionInternalApi.ENDPOINT,
                                    action,
                                    DelegatedIdentityContext.current(),
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    target),
                            new Scoped<>(scope, request)));
        } else {
            var current = SecurityUtils.requireUser();
            String token =
                    SecurityUtils.currentToken()
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
            var session =
                    tokens.of(Realm.MERCHANT)
                            .get(token)
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED))
                            .user();
            if (session == null
                    || current.realm() != Realm.MERCHANT
                    || session.realm() != Realm.MERCHANT
                    || !current.tenantNo().equals(session.tenantNo())
                    || current.subjectKind() != session.subjectKind()
                    || !current.userNo().equals(session.userNo())) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
        }
        var actor = access.requireMerchant(scope, BizPerms.CHECKOUT_PREPARE);
        access.requireMerchant(scope, BizPerms.ORDER_VIEW);
        var account =
                merchants
                        .resolveAuthorizedOperator(
                                new MerchantOperatorAccessPort.Query(
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        actor.actorNo(),
                                        actor.realm(),
                                        actor.subjectKind(),
                                        BizPerms.CHECKOUT_PREPARE))
                        .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        return new CashierActor(
                actor.tenantNo(),
                actor.entityNo(),
                actor.storeNo(),
                actor.actorNo(),
                actor.realm(),
                actor.subjectKind(),
                account.accountNo());
    }

    public ManualServicePurchasePolicy(QualificationPort qualifications) {
        this.qualifications = qualifications;
    }

    /**
     * Business eligibility remains required independently from payment-method/channel
     * configuration.
     */
    public void requireBusiness(CheckoutScope scope) {
        if (!qualifications.hasValidQualification(
                scope.entityNo(), QualificationPort.BUSINESS_LICENSE)) {
            throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
        }
    }

    public void requireSupported(PrepareCheckoutReq request) {
        if (request == null
                || !"OFFLINE".equals(request.payMode())
                || request.couponNo() != null
                || !request.activityChoices().isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }
}
