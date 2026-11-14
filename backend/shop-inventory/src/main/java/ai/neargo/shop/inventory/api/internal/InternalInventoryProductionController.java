package ai.neargo.shop.inventory.api.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryProductionInternalApi;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

/** Authenticated owner commands only; there is no public arbitrary material-deduction endpoint. */
@RestController
@ConditionalOnInventory
public class InternalInventoryProductionController {
    private final InventoryProductionPort production;
    private final String token;
    private final JsonMapper json;

    public InternalInventoryProductionController(
            InventoryProductionPort production,
            @Value("${shop.services.internal-token:}") String token,
            JsonMapper json) {
        this.production = production;
        this.token = token;
        this.json = json;
    }

    @PostMapping(InventoryProductionInternalApi.CONSUME)
    public ResponseEntity<?> consume(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, false);
    }

    @PostMapping(InventoryProductionInternalApi.QUERY)
    public ResponseEntity<?> query(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(body, given, true);
    }

    @PostMapping(InventoryProductionInternalApi.REVERSE)
    public ResponseEntity<?> reverse(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    final InventoryProductionPort.Reverse c;
                    try {
                        c = json.readValue(body, InventoryProductionPort.Reverse.class);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return production.reverse(c);
                });
    }

    private ResponseEntity<?> invoke(byte[] body, String given, boolean read) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    if (body == null || body.length == 0)
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    final InventoryProductionPort.Command c;
                    try {
                        c = json.readValue(body, InventoryProductionPort.Command.class);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return read ? production.query(c) : production.consume(c);
                });
    }
}
