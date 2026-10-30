package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;

/** Transport ambiguity never becomes empty business success or a replacement operation. */
final class BeautyReceptionCalls {
    private BeautyReceptionCalls() {}

    static <P, R> R call(
            String endpoint,
            String action,
            String entity,
            String store,
            String actor,
            String target,
            P request,
            InternalBindings bindings,
            Function<InternalCall<P>, InternalReply<R>> invoke) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current(actor);
        String realm = entity == null && store == null ? "CONSUMER" : "MERCHANT";
        if (!realm.equals(identity.realm())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1, endpoint, action, identity, entity, store, target),
                        request);
        final String digest;
        try {
            digest = bindings.digest(call);
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
        try {
            var reply = ServiceCalls.call(ServiceName.BEAUTY, () -> invoke.apply(call));
            bindings.require(call, reply);
            return BeautyReceptionResponses.require(call, reply.result());
        } catch (ServiceCallException failure) {
            ErrorCode code = business(failure, digest);
            throw BizException.of(code == null ? ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE : code);
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
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
                    case "MEMBER_CODE_INVALID" -> ErrorCode.MEMBER_CODE_INVALID;
                    case "FORBIDDEN" -> ErrorCode.FORBIDDEN;
                    case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
                    case "CONFLICT" -> ErrorCode.CONFLICT;
                    case "CHECKOUT_QUOTE_STALE" -> ErrorCode.CHECKOUT_QUOTE_STALE;
                    case "CHECKOUT_CONFIRMATION_REQUIRED" ->
                            ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED;
                    case "MANAGED_SERVICE_COMPLETION_BLOCKED" ->
                            ErrorCode.MANAGED_SERVICE_COMPLETION_BLOCKED;
                    case "CHECKOUT_OPERATION_REJECTED" -> ErrorCode.CHECKOUT_OPERATION_REJECTED;
                    case "BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE" ->
                            ErrorCode.BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE;
                    case "BEAUTY_SOURCE_CUSTOMER_REQUIRED" ->
                            ErrorCode.BEAUTY_SOURCE_CUSTOMER_REQUIRED;
                    case "CHECKOUT_UNSUPPORTED_SELECTION" ->
                            ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION;
                    case "BEAUTY_SEARCH_INCOMPLETE" -> ErrorCode.BEAUTY_SEARCH_INCOMPLETE;
                    case "CHECKOUT_DEPENDENCY_UNAVAILABLE" ->
                            ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE;
                    case "ENTITLEMENT_FEATURE_UNAVAILABLE" ->
                            ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE;
                    case "MEMBER_GUEST_UNAVAILABLE" -> ErrorCode.MEMBER_GUEST_UNAVAILABLE;
                    case "BEAUTY_DEPENDENCY_UNAVAILABLE" -> ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE;
                    default -> null;
                };
        if (code == null) {
            return null;
        }
        int expected =
                switch (code) {
                    case UNAUTHORIZED, TOKEN_EXPIRED -> 401;
                    case BAD_REQUEST, MEMBER_CODE_INVALID -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT,
                            CHECKOUT_QUOTE_STALE,
                            CHECKOUT_CONFIRMATION_REQUIRED,
                            MANAGED_SERVICE_COMPLETION_BLOCKED,
                            CHECKOUT_OPERATION_REJECTED,
                            BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE,
                            BEAUTY_SOURCE_CUSTOMER_REQUIRED ->
                            409;
                    case CHECKOUT_UNSUPPORTED_SELECTION, BEAUTY_SEARCH_INCOMPLETE -> 422;
                    default -> 503;
                };
        return failure.statusCode() == expected ? code : null;
    }
}
