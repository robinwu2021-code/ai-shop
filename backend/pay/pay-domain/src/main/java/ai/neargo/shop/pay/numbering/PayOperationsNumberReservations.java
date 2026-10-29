package ai.neargo.shop.pay.numbering;

import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_EXCEPTION;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_EXCEPTION_ACTION;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_GRANT;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_ISSUE_BATCH;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_MIGRATION_IMPORT;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_MIGRATION_PLAN;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_MIGRATION_STAGE;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_RECON_DIFF;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_RECON_RUN;
import static ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE;
import static ai.neargo.shop.numbering.DocumentType.LEGACY_COUPON_ISSUE;
import static ai.neargo.shop.numbering.DocumentType.PAYMENT_ATTEMPT;
import static ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER;
import static ai.neargo.shop.numbering.DocumentType.SETTLEMENT_BATCH;
import static ai.neargo.shop.numbering.DocumentType.SETTLEMENT_BILL;
import static ai.neargo.shop.numbering.DocumentType.SETTLEMENT_INVOICE_REQUEST;
import static ai.neargo.shop.numbering.DocumentType.WITHDRAWAL;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Command;
import ai.neargo.shop.spi.entitlement.LegacyCouponAudiencePort.Fact;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Reserves the complete bounded operation before Spring acquires its original business transaction.
 * Only explicit entry points participate; original isolation, propagation and remote-call order stay intact.
 */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class PayOperationsNumberReservations extends DefaultPointcutAdvisor {
    public PayOperationsNumberReservations(@Qualifier("payDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        super(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return owns(targetClass.getSimpleName(), method.getName());
            }
        }, (MethodInterceptor) invocation -> reserve(numbers, invocation));
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }
    static boolean owns(String type, String method) {
        return switch (type) {
            case "LegacyCouponIssueWriter" -> method.equals("prepare");
            case "EntitlementMigrationPlanWriter", "EntitlementMigrationStageWriter" -> method.equals("claim");
            case "LegacyCouponMigrationImportWriter" -> method.equals("importPlan");
            case "EntitlementReconciliationServiceImpl" -> method.equals("check") || method.equals("checkRights");
            case "EntitlementExceptionServiceImpl" -> method.equals("captureGrantFailure");
            case "EntitlementEventDeliveryServiceImpl" -> List.of("captureDead", "retryEvent", "queryEvent").contains(method);
            case "EntitlementExceptionActionWriter" -> method.equals("begin");
            case "WithdrawServiceImpl" -> method.equals("apply");
            case "SettleBatchServiceImpl" -> method.equals("collectIntoBatches");
            case "SettleServiceImpl" -> method.equals("generateForOrder") || method.equals("submitInvoice");
            case "PaymentLedgerServiceImpl" -> method.equals("open");
            case "PaymentServiceImpl" -> method.equals("start");
            case "LegacyCouponUseWriter" -> List.of("settle", "reverse", "replay", "repairUsage").contains(method);
            case "SettlementPointsFundingServiceImpl" -> method.equals("prepare") || method.equals("accept");
            case "SplitPreparationServiceImpl" -> method.equals("prepare");
            case "SplitResultServiceImpl" -> method.equals("acceptPoints");
            default -> false;
        };
    }
    static Map<DocumentType,Integer> budget(String type, String method, Object[] args) {
        return switch (type) {
            case "LegacyCouponIssueWriter" -> Map.of(LEGACY_COUPON_ISSUE, 1, ENTITLEMENT_ISSUE_BATCH, 1,
                    ENTITLEMENT_GRANT, Math.multiplyExact(((Fact) args[3]).recipients().size(), ((Command) args[1]).count()));
            case "EntitlementMigrationPlanWriter" -> Map.of(ENTITLEMENT_MIGRATION_PLAN, 1);
            case "EntitlementMigrationStageWriter" -> Map.of(ENTITLEMENT_MIGRATION_STAGE, 1);
            case "LegacyCouponMigrationImportWriter" -> Map.of(ENTITLEMENT_MIGRATION_IMPORT, 1,
                    ENTITLEMENT_USAGE, ((List<?>) args[7]).size());
            case "EntitlementReconciliationServiceImpl" -> Map.of(ENTITLEMENT_RECON_RUN, 1,
                    ENTITLEMENT_RECON_DIFF, args[1] instanceof Check c ? c.limit() : ((CheckRights) args[1]).limit());
            case "EntitlementExceptionServiceImpl" -> Map.of(ENTITLEMENT_EXCEPTION, 1);
            case "EntitlementEventDeliveryServiceImpl" -> method.equals("captureDead")
                    ? Map.of(ENTITLEMENT_EXCEPTION, 1) : Map.of(ENTITLEMENT_EXCEPTION_ACTION, 1);
            case "EntitlementExceptionActionWriter" -> Map.of(ENTITLEMENT_EXCEPTION_ACTION, 1);
            case "WithdrawServiceImpl" -> Map.of(WITHDRAWAL, 1);
            case "SettleBatchServiceImpl" -> Map.of(SETTLEMENT_BATCH, 500);
            case "SettleServiceImpl" -> method.equals("submitInvoice") ? Map.of(SETTLEMENT_INVOICE_REQUEST, 1)
                    : Map.of(SETTLEMENT_BILL, ((List<?>) args[1]).size());
            case "PaymentLedgerServiceImpl", "PaymentServiceImpl" -> Map.of(PAYMENT_ATTEMPT, 1);
            case "LegacyCouponUseWriter" -> Map.of(ENTITLEMENT_USAGE, 1);
            case "SettlementPointsFundingServiceImpl", "SplitPreparationServiceImpl", "SplitResultServiceImpl" -> Map.of(POINTS_VOUCHER, 1);
            default -> throw new IllegalArgumentException("Unknown operation number budget");
        };
    }
    private static Object reserve(DocumentNumberCoordinator numbers, MethodInvocation invocation) throws Throwable {
        try {
            String type = invocation.getThis().getClass().getSimpleName();
            // Spring's target is the original service; avoid relying on proxy-generated class names.
            type = ClassUtils.getUserClass(invocation.getThis()).getSimpleName();
            return numbers.withReservation(budget(type, invocation.getMethod().getName(), invocation.getArguments()), () -> {
                try { return invocation.proceed(); }
                catch (Throwable failure) { throw new InvocationFailure(failure); }
            });
        } catch (InvocationFailure failure) { throw failure.getCause(); }
    }
    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) { super(cause); }
    }
}
