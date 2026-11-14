package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.AttendanceConfirmation;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ConfirmAttendance;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Consume;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Entitlement;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Extension;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ExtensionReceipt;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Plan;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.PlanRevision;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.food.spi.ServiceEntitlementInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Remote deployment invokes the same original FOOD allowance service and never replaces missing
 * owners with success.
 */
public final class RemoteServiceEntitlementService implements ServiceEntitlementService {
    private final ServiceEntitlementInternalApi api;

    public RemoteServiceEntitlementService(ServiceEntitlementInternalApi api) {
        this.api = api;
    }

    @Override
    public Entitlement grant(String e, String s, String a, String f, Grant r, String k) {
        var result =
                invoke(
                        () ->
                                api.grant(
                                        new ServiceEntitlementInternalApi.GrantCommand(
                                                e, s, a, f, r, k)));
        entitlement(result, f, null);
        check(
                r.attendeeNo().equals(result.attendeeNo())
                        && r.goodsNo().equals(result.goodsNo())
                        && r.skuNo().equals(result.skuNo())
                        && r.quantity().compareTo(result.granted()) == 0
                        && r.quote().quoteNo().equals(result.quoteNo()));
        return result;
    }

    @Override
    public Entitlement consume(String e, String s, String a, String f, Consume r, String k) {
        var result =
                invoke(
                        () ->
                                api.consume(
                                        new ServiceEntitlementInternalApi.ConsumeCommand(
                                                e, s, a, f, r, k)));
        entitlement(result, null, f);
        return result;
    }

    @Override
    public List<Entitlement> entitlements(String e, String s, String f) {
        var result = invoke(() -> api.list(new ServiceEntitlementInternalApi.ListCommand(e, s, f)));
        check(result.size() <= 10000);
        var seen = new HashSet<String>();
        for (var item : result) {
            entitlement(item, f, null);
            check(seen.add(item.entitlementNo()));
        }
        return result;
    }

    @Override
    public ExtensionReceipt extend(String e, String s, String a, String f, Extension r, String k) {
        var result =
                invoke(
                        () ->
                                api.extend(
                                        new ServiceEntitlementInternalApi.ExtensionCommand(
                                                e, s, a, f, r, k)));
        check(
                f.equals(result.formNo())
                        && r.validUntil().equals(result.validUntil())
                        && result.beforeUntil() != null
                        && result.beforeUntil().isBefore(result.validUntil())
                        && r.quote().quoteNo().equals(result.quoteNo())
                        && result.formVersion() == Math.addExact(r.formVersion(), 1)
                        && result.capacityClaimNo() != null
                        && result.eventNo() != null);
        return result;
    }

    @Override
    public PlanRevision revisePlan(String e, String s, String a, String f, Plan r, String k) {
        var result =
                invoke(
                        () ->
                                api.plan(
                                        new ServiceEntitlementInternalApi.PlanCommand(
                                                e, s, a, f, r, k)));
        check(
                f.equals(result.formNo())
                        && result.planNo() != null
                        && result.revision() > 0
                        && result.intendedBudgetMinor() == r.intendedBudgetMinor()
                        && result.plannedPersons() == r.plannedPersons()
                        && result.formVersion() == Math.addExact(r.formVersion(), 1)
                        && result.contentHash() != null
                        && result.contentHash().matches("[a-f0-9]{64}"));
        return result;
    }

    @Override
    public AttendanceConfirmation confirmAttendance(
            String e, String s, String a, String f, ConfirmAttendance r, String k) {
        var result =
                invoke(
                        () ->
                                api.confirm(
                                        new ServiceEntitlementInternalApi.ConfirmCommand(
                                                e, s, a, f, r, k)));
        check(
                f.equals(result.formNo())
                        && r.planNo().equals(result.planNo())
                        && r.quote().quoteNo().equals(result.quoteNo())
                        && result.actualPersons() > 0
                        && result.calculatedReceivableMinor() >= 0
                        && result.formVersion() == Math.addExact(r.formVersion(), 1));
        return result;
    }

    private static void entitlement(Entitlement result, String form, String no) {
        check(
                result != null
                        && (form == null || form.equals(result.formNo()))
                        && (no == null || no.equals(result.entitlementNo()))
                        && result.entitlementNo() != null
                        && result.attendeeNo() != null
                        && result.granted() != null
                        && result.granted().signum() > 0
                        && result.consumed() != null
                        && result.consumed().signum() >= 0
                        && result.consumed().compareTo(result.granted()) <= 0
                        && result.validFrom() != null
                        && result.validUntil() != null
                        && result.validFrom().isBefore(result.validUntil())
                        && result.sourceHash() != null
                        && result.sourceHash().matches("[a-f0-9]{64}")
                        && result.unit() != null
                        && result.version() >= 0);
    }

    private static void check(boolean valid) {
        if (!valid) throw new IllegalStateException("Wrong original FOOD entitlement receipt");
    }

    private static <T> T invoke(Supplier<T> operation) {
        try {
            T result = ServiceCalls.call(ServiceName.FOOD, operation);
            if (result == null)
                throw new IllegalStateException("Missing original FOOD entitlement receipt");
            return result;
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                case 503 -> BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                default -> failure;
            };
        }
    }
}
