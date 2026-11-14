package ai.neargo.shop.inventory.api.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Change;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reference;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reserve;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Reverse;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanInternalApi.Withdraw;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

@RestController
@ConditionalOnInventory
public class InternalInventoryDemandPlanController {
    private final InventoryDemandPlanPort owner;
    private final String token;
    private final JsonMapper json;

    public InternalInventoryDemandPlanController(
            InventoryDemandPlanPort owner,
            @Value("${shop.services.internal-token:}") String token,
            JsonMapper json) {
        this.owner = owner;
        this.token = token;
        this.json = json;
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/reserve")
    public ResponseEntity<?> reserve(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Reserve.class,
                c -> owner.reserve(c.plan(), c.ttlSeconds(), c.lifecycleVersion()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/withdraw")
    public ResponseEntity<?> withdraw(
            @RequestBody byte[] body,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String header) {
        return call(
                body,
                header,
                Withdraw.class,
                command ->
                        owner.withdraw(
                                command.plan(),
                                command.operationRef(),
                                command.lifecycleVersion()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/query")
    public ResponseEntity<?> query(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(b, t, Reference.class, c -> owner.query(c.scope()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/accept-unpaid")
    public ResponseEntity<?> accept(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(b, t, Reference.class, c -> owner.acceptUnpaid(c.scope()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/allocate-paid")
    public ResponseEntity<?> allocate(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(b, t, Reference.class, c -> owner.allocatePaid(c.scope()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/issue-delta")
    public ResponseEntity<?> issueDelta(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Change.class,
                c ->
                        owner.issue(
                                c.scope(), c.operationRef(), c.planHash(), c.phase(), c.targets()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/issue")
    public ResponseEntity<?> issue(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Change.class,
                c ->
                        owner.issueTo(
                                c.scope(), c.operationRef(), c.planHash(), c.phase(), c.targets()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/release")
    public ResponseEntity<?> release(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Change.class,
                c -> owner.releaseTo(c.scope(), c.operationRef(), c.planHash(), c.targets()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/supplement")
    public ResponseEntity<?> supplement(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Change.class,
                c -> owner.supplement(c.scope(), c.operationRef(), c.planHash(), c.targets()));
    }

    @PostMapping(InventoryDemandPlanInternalApi.ROOT + "/reverse")
    public ResponseEntity<?> reverse(
            @RequestBody byte[] b,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String t) {
        return call(
                b,
                t,
                Reverse.class,
                c ->
                        owner.reverse(
                                c.scope(),
                                c.operationRef(),
                                c.originalOperationRef(),
                                c.planHash()));
    }

    private <T> ResponseEntity<?> call(
            byte[] b, String t, Class<T> type, Function<T, Object> action) {
        return InternalServiceResponses.invoke(
                token,
                t,
                () -> {
                    final T c;
                    try {
                        c = json.readValue(b, type);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return action.apply(c);
                });
    }
}
