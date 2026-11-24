package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.CounterSettlementService;
import ai.neargo.shop.food.spi.CounterSettlementInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 柜台后付结账的服务间入口：先验内部令牌，业务码随请求摘要回传（收银台要区分还差钱、账单变了等）。 */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalCounterSettlementController {
    private final CounterSettlementService service;
    private final String token;

    public InternalCounterSettlementController(CounterSettlementService service,
            @Value("${shop.services.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping(CounterSettlementInternalApi.ROOT + "/create")
    public ResponseEntity<?> create(@RequestBody CounterSettlementInternalApi.Create r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given, () -> CounterSettlementInternalApi.digest(r),
                () -> service.create(r.entity(), r.store(), r.actor(), r.checkNo(), r.key()));
    }

    @PostMapping(CounterSettlementInternalApi.ROOT + "/finalize")
    public ResponseEntity<?> finalizeSettlement(@RequestBody CounterSettlementInternalApi.Finalize r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given, () -> CounterSettlementInternalApi.digest(r),
                () -> service.finalize(r.entity(), r.store(), r.actor(), r.settlementNo(), r.offlineRecordNos()));
    }

    @PostMapping(CounterSettlementInternalApi.ROOT + "/cancel")
    public ResponseEntity<?> cancel(@RequestBody CounterSettlementInternalApi.Reference r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given,
                () -> CounterSettlementInternalApi.digest("cancel", r),
                () -> service.cancel(r.entity(), r.store(), r.actor(), r.settlementNo()));
    }

    @PostMapping(CounterSettlementInternalApi.ROOT + "/get")
    public ResponseEntity<?> get(@RequestBody CounterSettlementInternalApi.Reference r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(token, given,
                () -> CounterSettlementInternalApi.digest("get", r),
                () -> service.get(r.entity(), r.store(), r.settlementNo()));
    }
}
