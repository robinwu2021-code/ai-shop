package ai.neargo.svc.guard;

import ai.neargo.svc.context.SignedContext;
import java.util.Optional;

/**
 * 当前请求验过签的上下文。微服务从这里取操作人与范围，用于留痕和数据隔离；需要纵深防御的入口调一次
 * {@link #requirePoint}，不需要的不调。
 */
public final class SignedContextHolder {

    private static final ThreadLocal<SignedContext> CURRENT = new ThreadLocal<>();

    private SignedContextHolder() {
    }

    public static Optional<SignedContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 这次调用没带验过签的上下文，或者上下文里没有这个功能点，就抛。 */
    public static SignedContext requirePoint(String code) {
        SignedContext context = CURRENT.get();
        if (context == null || !context.hasPoint(code)) {
            throw new SecurityException("调用没有携带功能点 " + code);
        }
        return context;
    }

    static void set(SignedContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }
}
