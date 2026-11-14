package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Explicit reservation boundaries run before Spring's transaction interceptor; no transaction is added. */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class PayRightsNumberReservations extends DefaultPointcutAdvisor {
    private static final Map<String,Set<String>> POINTS = Map.ofEntries(
        entry("PointsPostingServiceImpl", "post"),
        entry("PointsOperationsPortImpl", "hold", "release", "prepareRefund", "authorizeRefund", "confirmRefund", "abortRefund", "compensateRefund"), entry("EarnServiceImpl", "grant", "grantAccepted"),
        entry("RedeemServiceImpl", "hold", "release"), entry("RefundClosureServiceImpl", "close"),
        entry("ExpiryServiceImpl", "prepare", "claim", "accept"), entry("TypeRetirementServiceImpl", "retire"),
        entry("PointTypeServiceImpl", "retire"), entry("PointsAdministrationServiceImpl", "transition"), entry("PoolServiceImpl", "applyApproved"),
        entry("PoolRequestServiceImpl", "approve"), entry("PointsTreasuryServiceImpl", "review"),
        entry("PointsAdjustmentServiceImpl", "review"),
        entry("PoolWithdrawalServiceImpl", "prepareApproved", "claimDispatch", "acceptResult"),
        entry("PointsPayoutServiceImpl", "beginPayout", "claimDispatch", "acceptResult"),
        entry("StatementPayoutServiceImpl", "prepare"),
        entry("PointsPaidFactServiceImpl", "onPaid"),
        entry("PointsSettlementHookImpl", "beginPayout", "acceptResult", "prepareForBill", "onBillFinalized"),
        entry("PointsSourceFactHandlerImpl", "apply", "earn", "confirm"),
        entry("PointsRefundAbortServiceImpl", "abort"), entry("PointsRefundPreparationServiceImpl", "prepare"),
        entry("PointsRefundCashDecisionServiceImpl", "authorize"), entry("PointsRefundZeroCashServiceImpl", "accept"),
        entry("PointsRefundConfirmationServiceImpl", "confirm"),
        entry("PointsRefundCollectionServiceImpl", "reviseFailed", "claim", "accept"),
        entry("PointsRefundCompensationServiceImpl", "prepare", "claim", "accept"),
        entry("PointsReceivableRecoveryServiceImpl", "prepare", "claim", "accept"),
        entry("StoreStatementServiceImpl", "draft", "draftGroups", "redraft", "confirm", "dispute", "reopenForRefund"),
        entry("StatementBuildServiceImpl", "advance"),
        entry("StatementConfirmationStateImpl", "confirm"),
        entry("PointsWorkbenchServiceImpl", "draftStatements", "changeStatement", "reviseRefund", "prepareReceivable")
    );
    public PayRightsNumberReservations(@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        super(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return targetClass.getName().startsWith("ai.neargo.shop.pay.")
                    && registered(targetClass.getSimpleName(), method.getName());
            }
        }, (MethodInterceptor) invocation -> {
            var counts = counts(ClassUtils.getUserClass(invocation.getThis()).getSimpleName(),
                    invocation.getMethod().getName(), invocation.getArguments());
            if (counts.isEmpty()) return invocation.proceed();
            return numbers.withReservation(counts, () -> {
                try { return invocation.proceed(); }
                catch (RuntimeException | Error e) { throw e; }
                catch (Throwable e) { throw new IllegalStateException(e); }
            });
        });
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }
    private static Map.Entry<String,Set<String>> entry(String name, String... methods) {
        return Map.entry(name, Set.of(methods));
    }
    private static boolean registered(String name, String method) {
        return POINTS.getOrDefault(name,Set.of()).contains(method)
            || name.equals("EntitlementGrantWriter") && method.equals("prepare")
            || name.equals("EntitlementIssueBatchWriter") && Set.of("create", "step").contains(method)
            || name.equals("EntitlementUseWriter") && method.equals("finish")
            || name.equals("StoredValuePostingServiceImpl") && method.equals("creditReceived")
            || name.equals("StoredValueRechargeWriterImpl") && Set.of("create", "cancel").contains(method)
            || name.equals("StoredValueRedemptionPostingServiceImpl") && method.equals("finish");
    }
    static Map<DocumentType,Integer> counts(String name, String method, Object[] args) {
        if (!registered(name,method)) return Map.of();
        if (name.equals("EntitlementGrantWriter")) return Map.of(DocumentType.ENTITLEMENT_GRANT,1);
        if (name.equals("EntitlementIssueBatchWriter")) {
            if (method.equals("step")) return Map.of(DocumentType.ENTITLEMENT_GRANT,1);
            var snapshot=(BatchSnapshot) args[4];
            int recipients=(int)snapshot.audience().recipients().stream().filter(r -> r.eligible()).count();
            return Map.of(DocumentType.ENTITLEMENT_ISSUE_BATCH,1,DocumentType.ENTITLEMENT_GRANT,recipients);
        }
        if (name.equals("EntitlementUseWriter") || name.equals("StoredValueRedemptionPostingServiceImpl")) {
            if (!Boolean.TRUE.equals(args[4])) return Map.of();
            return name.equals("EntitlementUseWriter") ? Map.of(DocumentType.ENTITLEMENT_USAGE,1)
                : Map.of(DocumentType.ENTITLEMENT_USAGE,1,DocumentType.SV_VOUCHER,1);
        }
        if (name.equals("StoredValueRechargeWriterImpl")) {
            // Source-less cancellation can only update an existing receipt; it also joins plan cancellation.
            if (method.equals("cancel") && args[3] == null) return Map.of();
            return Map.of(DocumentType.SV_RECHARGE,1);
        }
        if (name.equals("StoredValuePostingServiceImpl")) return Map.of(DocumentType.SV_VOUCHER,1);
        // One financial voucher per operation, except the two documented composed/bounded operations.
        int count=1;
        if (name.equals("PointsWorkbenchServiceImpl") && method.equals("draftStatements")) count=400;
        if (name.equals("PointsRefundPreparationServiceImpl") || name.equals("PointsOperationsPortImpl") && method.equals("prepareRefund")) count=2; // optional reopen + preparation
        if (name.equals("StatementBuildServiceImpl")) count=200; // PAGE_SIZE, <=200 new route heads
        if (name.equals("StoreStatementServiceImpl") && Set.of("draft", "draftGroups").contains(method))
            count=400; // <=200 new route heads plus <=200 completed heads published in this page
        return Map.of(DocumentType.POINTS_VOUCHER,count);
    }
}
