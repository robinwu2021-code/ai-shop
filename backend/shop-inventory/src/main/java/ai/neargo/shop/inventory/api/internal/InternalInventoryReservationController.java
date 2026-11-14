package ai.neargo.shop.inventory.api.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** PLATFORM-hosted provider authenticates before parsing; unavailable authority returns 503 without mutations. */
@RestController
@ConditionalOnInventory
public class InternalInventoryReservationController {
    private final ObjectProvider<InventoryReservationPort> provider;
    private final String token;
    private final JsonMapper mapper;

    public InternalInventoryReservationController(ObjectProvider<InventoryReservationPort> provider,
            @Value("${shop.services.internal-token:}") String token, JsonMapper mapper) {
        this.provider = provider;
        this.token = token;
        this.mapper = mapper;
    }

    @PostMapping(InventoryReservationInternalApi.ROOT + "/reserve")
    public ResponseEntity<?> reserve(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Reserve.class,
                request -> port().reserve(request.scope(), request.lines(), request.ttlSeconds(),
                        request.lifecycleVersion()));
    }
    @PostMapping(InventoryReservationInternalApi.ROOT + "/accept-unpaid")
    public ResponseEntity<?> acceptUnpaid(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Reference.class,
                request -> port().acceptUnpaid(request.scope()));
    }
    @PostMapping(InventoryReservationInternalApi.ROOT + "/allocate-paid")
    public ResponseEntity<?> allocatePaid(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Reference.class,
                request -> port().allocatePaid(request.scope()));
    }
    @PostMapping(InventoryReservationInternalApi.ROOT + "/issue")
    public ResponseEntity<?> issue(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Issue.class,
                request -> port().issue(request.scope(), request.fulfillmentRef()));
    }
    @PostMapping(InventoryReservationInternalApi.ROOT + "/cancel-all")
    public ResponseEntity<?> cancelAll(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Reference.class,
                request -> port().cancelAll(request.scope()));
    }
    @PostMapping(InventoryReservationInternalApi.ROOT + "/query")
    public ResponseEntity<?> query(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, InventoryReservationInternalApi.Reference.class,
                request -> port().query(request.scope()));
    }

    private InventoryReservationPort port() {
        InventoryReservationPort found = provider.getIfAvailable();
        if (found == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return found;
    }

    private <T> ResponseEntity<?> invoke(byte[] body, String given, Class<T> type,
                                        Function<T, InventoryReservationPort.Snapshot> operation) {
        return InternalServiceResponses.invoke(token, given, () -> {
            if (body == null || body.length == 0) throw BizException.of(ErrorCode.BAD_REQUEST);
            T request;
            try {
                request = mapper.readValue(body, type);
            } catch (JacksonException malformed) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            return operation.apply(request);
        });
    }
}
