package ai.neargo.shop.gateway.core.resource;

import ai.neargo.shop.gateway.core.end.Access;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 本进程的入口：从 Spring MVC 实际登记的处理方法里取，读运行时的注解值。
 *
 * <p>不扫源码：注解里的字符串拼接在运行时已经算好，而且 Spring 登记了什么才是真的对外行为。
 * 没有 {@code @PreAuthorize} 的入口记成「只需登录」；它是不是公开地址由各端的公开清单决定，不在这里判。
 */
@Component
public class LocalScanResourceProvider implements ResourceProvider {

    private final ObjectProvider<RequestMappingHandlerMapping> mapping;

    public LocalScanResourceProvider(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping) {
        this.mapping = mapping;
    }

    @Override
    public List<ApiResource> load(List<String> pathPrefixes) {
        List<ApiResource> out = new ArrayList<>();
        RequestMappingHandlerMapping handlers = mapping.getIfAvailable();
        if (handlers == null) {
            return out;
        }
        handlers.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                return;
            }
            List<String> verbs = info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList();
            for (String pattern : patterns.getPatternValues()) {
                if (pathPrefixes.stream().noneMatch(prefix -> pattern.startsWith(prefix + "/"))) {
                    continue;
                }
                for (String verb : verbs.isEmpty() ? List.of("*") : verbs) {
                    out.add(resource(verb, pattern, handler,
                            pattern.startsWith("/biz/") ? PermRuleParser.BIZ_METHODS : PermRuleParser.OPS_METHODS));
                }
            }
        });
        return out;
    }

    private static ApiResource resource(String verb, String pattern, HandlerMethod handler,
                                        java.util.Set<String> methods) {
        String where = handler.getBeanType().getName() + "#" + handler.getMethod().getName();
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        if (annotation == null) {
            return new ApiResource(verb, pattern, ApiResource.LOCAL, Access.LOGIN, null, where);
        }
        Optional<PermRule> rule = PermRuleParser.parse(annotation.value(), methods);
        return rule.map(r -> new ApiResource(verb, pattern, ApiResource.LOCAL, Access.POINT, r, where))
                .orElseGet(() -> new ApiResource(verb, pattern, ApiResource.LOCAL, Access.DEFER, null, where));
    }
}
