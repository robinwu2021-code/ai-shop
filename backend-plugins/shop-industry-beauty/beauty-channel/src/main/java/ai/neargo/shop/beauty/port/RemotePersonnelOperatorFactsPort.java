package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Complete request binding and exact authenticated namespace prevent a foreign success from
 * becoming personnel fact.
 */
public final class RemotePersonnelOperatorFactsPort implements PersonnelOperatorFactsPort {
    private final PersonnelOperatorInternalApi api;
    private final InternalBindings bindings;

    public RemotePersonnelOperatorFactsPort(
            PersonnelOperatorInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public Observation current(Query query) {
        DelegatedIdentityContext.outside();
        if (query == null) {
            throw new IllegalArgumentException("explicit operator query required");
        }
        var q = query.access();
        var identity = DelegatedIdentityContext.current();
        if (!identity.equals(
                new DelegatedIdentity(q.tenantNo(), q.realm(), q.subjectKind(), q.operatorNo()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var request =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                PersonnelOperatorInternalApi.ENDPOINT,
                                "CURRENT",
                                identity,
                                q.entityNo(),
                                q.storeNo(),
                                q.operatorNo()),
                        query);
        final String digest;
        try {
            digest = bindings.digest(request);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> api.current(request));
            bindings.require(request, reply);
            // Bound decoding checks every wire property before typed conversion, including explicit
            // nullable validTo.
            var result = reply.result();
            return new Observation(result.state(), result.observedAt(), result.personnel());
        } catch (ServiceCallException failure) {
            var code = business(failure, digest);
            throw BizException.of(code == null ? ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE : code);
        } catch (RuntimeException malformed) {
            throw unavailable();
        }
    }

    private static ErrorCode business(ServiceCallException failure, String digest) {
        if (failure.remoteErrorCode() == null
                || failure.requestDigest() == null
                || !MessageDigest.isEqual(
                        digest.getBytes(StandardCharsets.UTF_8),
                        failure.requestDigest().getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        ErrorCode code =
                switch (failure.remoteErrorCode()) {
                    case "UNAUTHORIZED" -> ErrorCode.UNAUTHORIZED;
                    case "TOKEN_EXPIRED" -> ErrorCode.TOKEN_EXPIRED;
                    case "BAD_REQUEST" -> ErrorCode.BAD_REQUEST;
                    case "FORBIDDEN" -> ErrorCode.FORBIDDEN;
                    case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
                    case "CONFLICT" -> ErrorCode.CONFLICT;
                    default -> null;
                };
        if (code == null) {
            return null;
        }
        int status =
                switch (code) {
                    case UNAUTHORIZED, TOKEN_EXPIRED -> 401;
                    case BAD_REQUEST -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    default -> 503;
                };
        return failure.statusCode() == status ? code : null;
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }
}
