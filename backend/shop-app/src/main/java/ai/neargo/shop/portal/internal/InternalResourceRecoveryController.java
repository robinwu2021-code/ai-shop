package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Machine credentials guard private owner reads; explicit delegated actor facts remain mandatory. */
@RestController
public class InternalResourceRecoveryController {
    private final MerchantOperatorAccessPort access;
    private final ResourceCommandFactsPort commands;
    private final String token;
    public InternalResourceRecoveryController(MerchantOperatorAccessPort access, ResourceCommandFactsPort commands,
            @Value("${shop.services.internal-token:}") String token) {
        this.access = access;
        this.commands = commands;
        this.token = token;
    }
    /** A false result is an explicit denied fact; an unavailable owner remains an HTTP failure. */
    @PostMapping(ResourceRecoveryInternalApi.ACCESS_PATH)
    public ResponseEntity<?> operatorAccess(@RequestBody MerchantOperatorAccessPort.Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> access.allowed(query));
    }
    /** Returns only original command evidence, never a mutation endpoint or a resource/source permission. */
    @PostMapping(ResourceRecoveryInternalApi.COMMAND_PATH)
    public ResponseEntity<?> command(@RequestBody ResourceSourceAuthorityPort.Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> commands.lookup(request));
    }
    private ResponseEntity<?> invoke(String given, Supplier<?> read) {
        try { return InternalServiceResponses.invoke(token, given, () -> owner(read)); }
        catch (RuntimeException unavailable) {
            // The global public advice returns a 200 error envelope; it must not masquerade as command absence.
            return ResponseEntity.status(503).build();
        }
    }
    private <T> T owner(Supplier<T> read) {
        try { return read.get(); }
        catch (IllegalStateException conflict) { throw BizException.of(ErrorCode.CONFLICT); }
    }
}
