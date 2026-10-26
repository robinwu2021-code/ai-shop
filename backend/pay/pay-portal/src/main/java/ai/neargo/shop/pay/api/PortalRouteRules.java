package ai.neargo.shop.pay.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 一个 controller 类有没有落在它所在入口的前缀下（ADR-044 §2-3；TDD-支付服务-I0地基 AC3）。
 *
 * <p>为什么要查：五个入口的鉴权链按前缀挂。一个放在 {@code api.open} 包里、却映射到 {@code /biz/pay/**} 的 controller，
 * 会被商家会话那条链放行，而它的作者以为自己写的是验签接口 —— 编译、启动、调用都不会报错。
 *
 * <p>判据读的是**运行时注解值**（常量已内联），不是源码文本：{@code @RequestMapping(PayPaths.OPS)} 这种写法也查得到。
 */
public final class PortalRouteRules {

    private PortalRouteRules() {
    }

    /** 返回这个类的全部违规；空列表 = 合规。非 controller 不查。 */
    public static List<String> violations(Class<?> type) {
        if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
            return List.of();
        }
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] paths = mapping == null ? new String[0] : mapping.path();
        return violations(type.getName(), type.getPackageName(), paths);
    }

    /** 纯规则，便于用字符串直接测正反例 */
    static List<String> violations(String className, String packageName, String[] classLevelPaths) {
        List<String> out = new ArrayList<>();
        var face = PortalFace.ofPackage(packageName);
        if (face.isEmpty()) {
            out.add(className + " 不在任何入口子包下（" + PortalFace.API_PACKAGE + ".{ops,open,terminal,biz,checkout}）");
            return out;
        }
        if (classLevelPaths.length == 0) {
            out.add(className + " 没有类级 @RequestMapping —— 每个方法的路径各写各的，入口前缀就无从保证");
            return out;
        }
        for (String path : classLevelPaths) {
            if (!face.get().owns(path)) {
                out.add(className + " 映射到 " + path + "，不在 " + face.get() + " 的前缀 " + face.get().prefix() + " 下");
            }
        }
        return out;
    }
}
