package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.ServiceEntitlementContracts.AttendanceConfirmation;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ConfirmAttendance;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Consume;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Entitlement;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Extension;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ExtensionReceipt;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Plan;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.PlanRevision;

import java.util.List;

/**
 * Owns nonfinancial buffet allowances and versioned banquet planning; shared CORE remains the sole
 * quote owner.
 */
public interface ServiceEntitlementService {
    Entitlement grant(
            String entity, String store, String actor, String formNo, Grant request, String key);

    Entitlement consume(
            String entity,
            String store,
            String actor,
            String entitlementNo,
            Consume request,
            String key);

    List<Entitlement> entitlements(String entity, String store, String formNo);

    ExtensionReceipt extend(
            String entity,
            String store,
            String actor,
            String formNo,
            Extension request,
            String key);

    PlanRevision revisePlan(
            String entity, String store, String actor, String formNo, Plan request, String key);

    AttendanceConfirmation confirmAttendance(
            String entity,
            String store,
            String actor,
            String formNo,
            ConfirmAttendance request,
            String key);
}
