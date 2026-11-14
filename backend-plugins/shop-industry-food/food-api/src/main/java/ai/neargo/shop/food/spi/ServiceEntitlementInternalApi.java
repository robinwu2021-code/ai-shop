package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.ServiceEntitlementContracts.AttendanceConfirmation;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ConfirmAttendance;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Consume;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Entitlement;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Extension;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.ExtensionReceipt;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Grant;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.Plan;
import ai.neargo.shop.food.dto.ServiceEntitlementContracts.PlanRevision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Typed private operational transport preserves original caller scope, versions and actual quote
 * references.
 */
public interface ServiceEntitlementInternalApi {
    record GrantCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String formNo,
            @Valid @NotNull Grant request,
            @NotBlank String key) {}

    record ConsumeCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String entitlementNo,
            @Valid @NotNull Consume request,
            @NotBlank String key) {}

    record ListCommand(@NotBlank String entity, @NotBlank String store, @NotBlank String formNo) {}

    record ExtensionCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String formNo,
            @Valid @NotNull Extension request,
            @NotBlank String key) {}

    record PlanCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String formNo,
            @Valid @NotNull Plan request,
            @NotBlank String key) {}

    record ConfirmCommand(
            @NotBlank String entity,
            @NotBlank String store,
            @NotBlank String actor,
            @NotBlank String formNo,
            @Valid @NotNull ConfirmAttendance request,
            @NotBlank String key) {}

    @PostExchange("/internal/food/service-entitlements/grant")
    Entitlement grant(@RequestBody GrantCommand command);

    @PostExchange("/internal/food/service-entitlements/consume")
    Entitlement consume(@RequestBody ConsumeCommand command);

    @PostExchange("/internal/food/service-entitlements/list")
    List<Entitlement> list(@RequestBody ListCommand command);

    @PostExchange("/internal/food/service-entitlements/extend")
    ExtensionReceipt extend(@RequestBody ExtensionCommand command);

    @PostExchange("/internal/food/service-entitlements/banquet-plan")
    PlanRevision plan(@RequestBody PlanCommand command);

    @PostExchange("/internal/food/service-entitlements/confirm-attendance")
    AttendanceConfirmation confirm(@RequestBody ConfirmCommand command);
}
