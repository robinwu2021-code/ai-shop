package ai.neargo.shop.auth;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/**
 * A single token-first lifecycle keeps every delegated route on the same context-cleanup contract.
 */
public final class BoundDelegatedCalls {
    private final String token;
    private final InternalBindings bindings;

    /** Route-owned realm selection does not grant business permissions. */
    public enum Mode {
        MERCHANT,
        CONSUMER
    }

    /**
     * Blank token construction remains valid for default retail and disabled industry deployments.
     */
    public BoundDelegatedCalls(String internalToken) {
        this.token = internalToken;
        this.bindings = new InternalBindings(internalToken);
    }

    /** Only authenticated, route-bound calls may enter the existing delegated context. */
    public <T> ResponseEntity<?> invoke(
            String given,
            InternalCall<?> call,
            String expectedEndpoint,
            String expectedAction,
            Mode mode,
            String expectedTargetNo,
            Supplier<T> operation) {
        return InternalServiceResponses.invokeBound(
                token,
                given,
                () -> bindings.digest(call),
                () -> {
                    bindings.requireRoute(
                            call, expectedEndpoint, expectedAction, mode.name(), expectedTargetNo);
                    return BoundActionContext.call(
                            call,
                            bindings::digest,
                            () ->
                                    DelegatedIdentityContext.call(
                                            call.binding().identity(),
                                            () -> bindings.reply(call, operation.get())));
                });
    }
}
