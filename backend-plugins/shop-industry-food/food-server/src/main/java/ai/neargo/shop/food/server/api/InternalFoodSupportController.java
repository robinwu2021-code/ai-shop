package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.food.spi.FoodSupportInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal token protects the typed support provider; platform scope is proven at its caller. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class InternalFoodSupportController {
    private final FoodSupportService service;
    private final String token;

    public InternalFoodSupportController(
            FoodSupportService service, @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(FoodSupportInternalApi.ROOT + "/readiness")
    public ResponseEntity<?> readiness(
            @Valid @RequestBody FoodSupportInternalApi.ReadinessQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, service::readiness);
    }

    @PostMapping(FoodSupportInternalApi.ROOT + "/cases")
    public ResponseEntity<?> cases(
            @Valid @RequestBody FoodSupportInternalApi.CasesQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.cases(request.entityNo(), request.storeNo(), request.status()));
    }

    @PostMapping(FoodSupportInternalApi.ROOT + "/source")
    public ResponseEntity<?> evidence(
            @Valid @RequestBody FoodSupportInternalApi.SourceQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> service.evidence(request.entityNo(), request.storeNo(), request.source()));
    }
}
