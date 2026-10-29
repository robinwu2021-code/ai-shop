package ai.neargo.shop.retail.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.retail.commerce.service.RetailChannelOrderService;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.RetailChannelOrderIngestInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.RetailChannelOrderSourceInternalApi;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

/**
 * Signed SYSTEM body and service token are verified before any original industry owner operation.
 */
@RestController
public final class InternalRetailChannelOrderController {
    private final RetailChannelOrderInternalSecurity security;
    private final RetailChannelOrderService orders;
    private final ObjectMapper json;

    public InternalRetailChannelOrderController(
            RetailChannelOrderInternalSecurity security,
            RetailChannelOrderService orders,
            ObjectMapper json) {
        this.security = security;
        this.orders = orders;
        this.json = json;
    }

    @PostMapping("/internal/retail/channel-orders/prepare")
    public ResponseEntity<?> prepare(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-orders/prepare",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Command.class);
                    return orders.prepareImport(value);
                });
    }

    @PostMapping("/internal/retail/channel-orders/get")
    public ResponseEntity<?> get(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-orders/get",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Reference.class);
                    return orders.getImport(value);
                });
    }

    @PostMapping("/internal/retail/channel-orders/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-orders/confirm",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Confirmation.class);
                    return orders.confirmAcceptance(value);
                });
    }

    @PostMapping("/internal/retail/channel-orders/cancel")
    public ResponseEntity<?> cancel(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-orders/cancel",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, RetailChannelOrderIngestInternalApi.Cancel.class);
                    return orders.cancelPreparedImport(value.reference(), value.reason());
                });
    }

    @PostMapping("/internal/retail/channel-source/capture")
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-source/capture",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return orders.capture(value);
                });
    }

    @PostMapping("/internal/retail/channel-source/collection")
    public ResponseEntity<?> collection(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-source/collection",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return orders.collection(value);
                });
    }

    @PostMapping("/internal/retail/channel-source/acceptance")
    public ResponseEntity<?> acceptance(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-source/acceptance",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return orders.acceptance(value);
                });
    }

    @PostMapping("/internal/retail/channel-source/withdrawal")
    public ResponseEntity<?> withdrawal(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-source/withdrawal",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return orders.withdrawal(value);
                });
    }

    @PostMapping("/internal/retail/channel-source/demand")
    public ResponseEntity<?> demand(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/retail/channel-source/demand",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, RetailChannelOrderSourceInternalApi.Demand.class);
                    return orders.demandPlan(
                            value.reference(), value.reservationRef(), value.subOrderNo());
                });
    }
}
