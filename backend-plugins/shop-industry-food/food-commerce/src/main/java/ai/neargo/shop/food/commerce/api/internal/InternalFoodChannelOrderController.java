package ai.neargo.shop.food.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.food.commerce.port.FoodChannelOrderIngestPortImpl;
import ai.neargo.shop.food.commerce.port.FoodIndustryChannelOrderSourcePortImpl;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestInternalApi;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourceInternalApi;
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
public final class InternalFoodChannelOrderController {
    private final FoodCommerceInternalSecurity security;
    private final FoodChannelOrderIngestPortImpl orders;
    private final FoodIndustryChannelOrderSourcePortImpl source;
    private final ObjectMapper json;

    public InternalFoodChannelOrderController(
            FoodCommerceInternalSecurity security,
            FoodChannelOrderIngestPortImpl orders,
            FoodIndustryChannelOrderSourcePortImpl source,
            ObjectMapper json) {
        this.security = security;
        this.orders = orders;
        this.source = source;
        this.json = json;
    }

    @PostMapping("/internal/food/channel-orders/prepare")
    public ResponseEntity<?> prepare(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-orders/prepare",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Command.class);
                    return orders.prepareImport(value);
                });
    }

    @PostMapping("/internal/food/channel-orders/get")
    public ResponseEntity<?> get(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-orders/get",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Reference.class);
                    return orders.getImport(value);
                });
    }

    @PostMapping("/internal/food/channel-orders/confirm")
    public ResponseEntity<?> confirm(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-orders/confirm",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestPort.Confirmation.class);
                    return orders.confirmAcceptance(value);
                });
    }

    @PostMapping("/internal/food/channel-orders/cancel")
    public ResponseEntity<?> cancel(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-orders/cancel",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, ChannelOrderIngestInternalApi.Cancel.class);
                    return orders.cancelPreparedImport(value.reference(), value.reason());
                });
    }

    @PostMapping("/internal/food/channel-source/capture")
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-source/capture",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return source.capture(value);
                });
    }

    @PostMapping("/internal/food/channel-source/collection")
    public ResponseEntity<?> collection(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-source/collection",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return source.collection(value);
                });
    }

    @PostMapping("/internal/food/channel-source/acceptance")
    public ResponseEntity<?> acceptance(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-source/acceptance",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return source.acceptance(value);
                });
    }

    @PostMapping("/internal/food/channel-source/withdrawal")
    public ResponseEntity<?> withdrawal(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-source/withdrawal",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderPort.Reference.class);
                    return source.withdrawal(value);
                });
    }

    @PostMapping("/internal/food/channel-source/demand")
    public ResponseEntity<?> demand(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FoodScopeCredentials.HEADER, required = false) String scope,
            @RequestBody byte[] body) {
        return security.invoke(
                token,
                scope,
                "/internal/food/channel-source/demand",
                body,
                () -> {
                    var value =
                            CatalogSourceTransportPolicy.read(
                                    json, body, IndustryChannelOrderSourceInternalApi.Demand.class);
                    return source.demandPlan(
                            value.reference(), value.reservationRef(), value.subOrderNo());
                });
    }
}
