package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.food.spi.BottleCustodyInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The existing service token precedes original custody scope and durable command checks. */
@RestController
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.custody.enabled"},
        havingValue = "true")
public final class InternalBottleCustodyController {
    private final BottleCustodyService service;
    private final String token;

    public InternalBottleCustodyController(
            BottleCustodyService service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping("/internal/food/bottle-custody/deposit")
    public ResponseEntity<?> deposit(
            @RequestBody BottleCustodyInternalApi.DepositCommand c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.deposit(c.entity(), c.store(), c.actor(), c.request(), c.key()));
    }

    @PostMapping("/internal/food/bottle-custody/withdraw")
    public ResponseEntity<?> withdraw(
            @RequestBody BottleCustodyInternalApi.WithdrawCommand c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () ->
                        service.withdraw(
                                c.entity(),
                                c.store(),
                                c.actor(),
                                c.custodyNo(),
                                c.request(),
                                c.key()));
    }

    @PostMapping("/internal/food/bottle-custody/get")
    public ResponseEntity<?> get(
            @RequestBody BottleCustodyInternalApi.Query c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token, given, () -> service.get(c.entity(), c.store(), c.custodyNo()));
    }

    @PostMapping("/internal/food/bottle-custody/list")
    public ResponseEntity<?> list(
            @RequestBody BottleCustodyInternalApi.Query c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.list(c.entity(), c.store(), c.customerNo(), c.page(), c.size()));
    }

    @PostMapping("/internal/food/bottle-custody/movements")
    public ResponseEntity<?> movements(
            @RequestBody BottleCustodyInternalApi.Query c,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.movements(c.entity(), c.store(), c.custodyNo(), c.page(), c.size()));
    }
}
