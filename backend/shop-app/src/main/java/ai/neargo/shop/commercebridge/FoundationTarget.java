package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.shop.auth.BizContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request-local selection established from fresh host authority before MVC dispatch. */
public record FoundationTarget(String entityNo, String storeNo, boolean includesNewModel) {
    private static final String KEY = FoundationTarget.class.getName();

    static void bind(HttpServletRequest request, BizContext strict, boolean includesNewModel) {
        var target =
                new FoundationTarget(
                        strict.merchantNo(), strict.currentStoreNo(), includesNewModel);
        for (String value : java.util.Collections.list(request.getHeaders("X-Store-No")))
            target.check(null, value);
        for (String value : java.util.Collections.list(request.getHeaders("X-Entity-No")))
            target.check(value, null);
        for (String value : request.getParameterMap().getOrDefault("entityNo", new String[0]))
            target.check(value, null);
        for (String value : request.getParameterMap().getOrDefault("storeNo", new String[0]))
            target.check(null, value);
        request.setAttribute(KEY, target);
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
        if (entity != null && !entity.isBlank() && !entityNo.equals(entity)
                || store != null && !store.isBlank() && !storeNo.equals(store))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
    }
}
