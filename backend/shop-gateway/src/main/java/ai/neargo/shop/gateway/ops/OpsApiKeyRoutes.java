package ai.neargo.shop.gateway.ops;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.gateway.core.config.ConditionalOnGatewayEnd;
import ai.neargo.shop.platform.OpsApiKeyService.Access;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnGatewayEnd(end = "ops", legacyProfiles = "ops")
class OpsApiKeyRoutes implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(OpsApiKeyRoutes.class);

    private enum Admission { SCOPED_READ, PLATFORM_READ, PLATFORM_MANAGE }

    // Keys are MVC route templates. Match the selected handler, including static/variable precedence.
    private static final Map<String, Admission> ROUTES = Map.ofEntries(
            Map.entry("GET /ops/auth/me", Admission.SCOPED_READ),
            Map.entry("GET /ops/stores", Admission.SCOPED_READ),
            Map.entry("GET /ops/stores/{storeNo}", Admission.SCOPED_READ),
            Map.entry("POST /ops/stores/{storeNo}/restore", Admission.PLATFORM_MANAGE),
            Map.entry("GET /ops/merchants", Admission.SCOPED_READ),
            Map.entry("GET /ops/merchants/{merchantNo}", Admission.SCOPED_READ),
            Map.entry("POST /ops/merchants/{merchantNo}/status", Admission.PLATFORM_MANAGE),
            Map.entry("GET /ops/merchants/{merchantNo}/fulfillment", Admission.PLATFORM_READ),
            Map.entry("POST /ops/stores/{storeNo}/channels/{channel}/lock", Admission.PLATFORM_MANAGE),
            Map.entry("POST /ops/stores/{storeNo}/channels/{channel}/unlock", Admission.PLATFORM_MANAGE),
            Map.entry("GET /ops/logistics/channels", Admission.PLATFORM_READ),
            Map.entry("GET /ops/settle/pay-channels", Admission.PLATFORM_READ),
            Map.entry("GET /ops/industries", Admission.PLATFORM_READ),
            Map.entry("GET /ops/service-scopes", Admission.PLATFORM_READ),
            Map.entry("POST /ops/industries/{industry}/enabled", Admission.PLATFORM_MANAGE),
            Map.entry("POST /ops/service-scopes/{scope}/enabled", Admission.PLATFORM_MANAGE));

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !(authentication.getDetails() instanceof OpsApiKeyAuthFilter.Credential key)) {
                    return true;
                }
                String operation = request.getMethod() + " "
                        + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                Admission admission = ROUTES.get(operation);
                var user = (LoginUser) authentication.getPrincipal();
                if (!(handler instanceof HandlerMethod) || admission == null
                        || (admission != Admission.SCOPED_READ && !user.dataScope().all())
                        || (admission == Admission.PLATFORM_MANAGE && key.access() != Access.MANAGE)) {
                    throw new AccessDeniedException("Operation unavailable to this operator API key");
                }
                LOG.info("运营 API 密钥请求 keyNo={} staffNo={} operation={}", key.keyNo(), user.userNo(), operation);
                return true;
            }
        });
    }
}
