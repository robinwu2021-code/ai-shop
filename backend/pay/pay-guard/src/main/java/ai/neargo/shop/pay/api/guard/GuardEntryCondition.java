package ai.neargo.shop.pay.api.guard;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ConditionalOnGuardEntry} 的判定：读 {@code shop.pay.guard.entries}（逗号分隔，去空格，不分大小写）。
 *
 * <p><b>不配置 = 内嵌形态</b>：对外入口全装、内部入口不装 —— 这正是 {@code shop-app} 今天的样子，
 * 所以主应用一行配置不加也不变行为；{@code pay-svc} 显式配 {@code INTERNAL}。
 *
 * <p><b>写错入口名启动即失败</b>：静默忽略的话，一个拼错的入口就等于没有鉴权器，请求落到 404，看起来像路由问题。
 */
public final class GuardEntryCondition implements Condition {

    static final String PROPERTY = "shop.pay.guard.entries";

    /** 内嵌形态：除内部入口外全部 */
    static final Set<PortalFace> EMBEDDED_DEFAULT = EnumSet.complementOf(EnumSet.of(PortalFace.INTERNAL));

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attrs = metadata.getAnnotationAttributes(ConditionalOnGuardEntry.class.getName());
        PortalFace entry = (PortalFace) attrs.get("value");
        return enabled(context.getEnvironment()).contains(entry);
    }

    /** 本进程装了哪些入口 */
    public static Set<PortalFace> enabled(Environment env) {
        List<String> configured = Binder.get(env).bind(PROPERTY, Bindable.listOf(String.class)).orElse(null);
        if (configured == null) {
            return EMBEDDED_DEFAULT;
        }
        Set<PortalFace> out = EnumSet.noneOf(PortalFace.class);
        for (String raw : configured) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                out.add(PortalFace.valueOf(name));
            } catch (IllegalArgumentException e) {
                // 不挂 cause：根因要是这句（带可选值），而不是 valueOf 的「No enum constant」
                throw new IllegalStateException(PROPERTY + " 里有不认识的入口「" + raw.trim() + "」，可选："
                        + Arrays.toString(PortalFace.values()));
            }
        }
        return out;
    }
}
