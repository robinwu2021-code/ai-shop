package ai.neargo.shop.auth;

import ai.neargo.shop.common.GlobalExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 读取当前登录主体的<b>唯一入口</b>。三层都经这里，谁都不直接碰 {@code SecurityContextHolder}
 * —— 否则业务层就和 Spring Security 焊死，单测要起半个安全上下文才能跑。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<LoginUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** 需要登录的场景：取不到直接 401，不返回 null 让调用方去判。 */
    public static LoginUser requireUser() {
        return currentUser().orElseThrow(GlobalExceptionHandler.UnauthorizedException::new);
    }

    /**
     * 当前用户，未登录返回 null。
     *
     * <p>与 {@link #currentUserNo()} 的区别是「未登录是不是错误」：门店主页游客可看，
     * 未登录只是少了个性化内容，不该抛 401。两个方法分开是刻意的 ——
     * 只有一个方法的话，调用方会用 try-catch 来表达「可选登录」，那种写法一定会吞掉真异常。
     */
    public static String currentUserNoOrNull() {
        return currentUser().map(LoginUser::userNo).orElse(null);
    }

    public static String currentUserNo() {
        return requireUser().userNo();
    }
}
