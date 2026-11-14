package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.spi.FoodPrintInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Explicit service-token admission precedes original food scope checks. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalFoodPrintController {
    private final FoodPrintService service;
    private final String token;
    public InternalFoodPrintController(FoodPrintService service,@Value("${shop.services.internal-token:}") String token) {
        this.service=service; this.token=token;
    }
    @PostMapping(FoodPrintInternalApi.PATH)
    public ResponseEntity<?> enqueue(@RequestBody FoodPrintInternalApi.Command command,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER,required=false) String given) {
        return InternalServiceResponses.invoke(token,given,
                () -> service.enqueue(command.scope(),command.definition(),command.requestKey()));
    }
}
