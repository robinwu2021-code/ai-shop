package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.shop.auth.BizContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-local selection established from fresh host authority before MVC dispatch. */
public record FoundationTarget(String entityNo, String storeNo) {
    private static final String KEY = FoundationTarget.class.getName();

    static void bindAccount(HttpServletRequest request) {
        new FoundationTarget(null, null).checkRequest(request);
    }

    static void bind(HttpServletRequest request, BizContext strict) {
        var target = new FoundationTarget(strict.merchantNo(), strict.currentStoreNo());
        target.checkRequest(request);
        request.setAttribute(KEY, target);
    }

    /**
     * With the overlay disabled, the legacy resolver may fall back to a different store. Verify the
     * explicit request before handing its entity to the application transaction.
     */
    public static String applicationEntity(
            HttpServletRequest request, String bodyEntity, String bodyStore) {
        var target =
                current()
                        .orElseGet(
                                () -> {
                                    var requested =
                                            request.getHeader(
                                                    ai.neargo.shop.auth.BizContextFilter
                                                            .STORE_HEADER);
                                    if (requested == null || requested.isBlank())
                                        return new FoundationTarget(null, null);
                                    var ctx = BizContext.current();
                                    if (!requested.equals(ctx.currentStoreNo()))
                                        throw new AccessDenied(AccessStatus.FORBIDDEN);
                                    return new FoundationTarget(
                                            BizContext.requireMerchantNo(), requested);
                                });
        target.checkRequest(request);
        target.check(bodyEntity, bodyStore);
        return target.entityNo();
    }

    private void checkRequest(HttpServletRequest request) {
        for (String value : java.util.Collections.list(request.getHeaders("X-Store-No")))
            check(null, value);
        for (String value : java.util.Collections.list(request.getHeaders("X-Entity-No")))
            check(value, null);
        for (String value : request.getParameterMap().getOrDefault("entityNo", new String[0]))
            check(value, null);
        for (String value : request.getParameterMap().getOrDefault("storeNo", new String[0]))
            check(null, value);
    }

    public static java.util.Optional<FoundationTarget> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(KEY) instanceof FoundationTarget target)
            return java.util.Optional.of(target);
        return java.util.Optional.empty();
    }

    public static void requireMatching(String entity, String store) {
        current().ifPresent(t -> t.check(entity, store));
    }

    private void check(String entity, String store) {
        if (entity != null && !entity.isBlank() && !entity.equals(entityNo)
                || store != null && !store.isBlank() && !store.equals(storeNo))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
    }
}
