package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.LoginUser;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Adapts a token-verified platform delegation to the existing audit handler and always restores thread state. */
final class InternalBeautyActor {
    private InternalBeautyActor() { }
    static <T> T call(String actor, Supplier<T> operation) {
        var previous = SecurityContextHolder.getContext();
        var delegated = SecurityContextHolder.createEmptyContext();
        delegated.setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant(actor, actor), null, List.of()));
        try {
            SecurityContextHolder.setContext(delegated);
            return operation.get();
        } finally { SecurityContextHolder.setContext(previous); }
    }
}
