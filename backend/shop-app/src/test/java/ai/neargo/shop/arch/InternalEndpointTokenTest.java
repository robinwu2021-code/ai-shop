package ai.neargo.shop.arch;

import ai.neargo.job.api.JobHttpPaths;
import ai.neargo.shop.svc.InternalHttp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每个 {@code portal.internal} 端点都必须收服务令牌头。
 *
 * <p>{@code /internal/**} 在安全链上是放行的，鉴权全靠端点自己比对
 * {@code shop.services.internal-token}（或 job 侧的 {@code X-Job-Token}）。
 * C8：27 个端点里 {@code InternalPricingFeeScopeEndpoint} 漏了这一步，任何能连到 shop-app
 * 的人都能按 entity+store 读改价申请与原单引用，而没有任何地方会说。
 *
 * <p>判据：每个映射方法都有一个 {@code @RequestHeader} 参数，名字是两种令牌头之一。
 * 收了头却不比对的写法这里看不出来 —— 各端点的单测负责「无令牌/错令牌回 401」，这道闸只防「忘了收」。
 */
class InternalEndpointTokenTest {
    private static final String PACKAGE = "ai.neargo.shop.portal.internal";
    private static final Set<String> TOKEN_HEADERS = Set.of(InternalHttp.TOKEN_HEADER, JobHttpPaths.TOKEN_HEADER);

    @Test void everyInternalHandlerTakesTheServiceTokenHeader() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        int handlers = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents(PACKAGE)) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) continue;
                handlers++;
                if (!takesToken(m)) missing.add(type.getSimpleName() + "#" + m.getName());
            }
        }
        assertThat(handlers).as("scan found no internal handlers — guard would pass vacuously").isGreaterThan(50);
        assertThat(missing).as("internal endpoints without a service-token header").isEmpty();
    }

    private static boolean takesToken(Method m) {
        for (Parameter p : m.getParameters()) {
            RequestHeader h = p.getAnnotation(RequestHeader.class);
            if (h != null && (TOKEN_HEADERS.contains(h.value()) || TOKEN_HEADERS.contains(h.name()))) return true;
        }
        return false;
    }
}
