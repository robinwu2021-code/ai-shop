package ai.neargo.shop.pay.api.guard.web;

import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import ai.neargo.shop.pay.api.guard.terminal.TerminalAuthService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * controller 只能经参数拿到「谁在调」—— 守卫认出、解析器换成 {@code PayCaller}，不接受任何「我是谁」的入参
 * （TDD-支付服务-I5接入层 F02）。开放 API 的服务按入口开关装配，不在本进程时用到即失败（{@link ObjectProvider}）。
 */
@Configuration
public class GuardWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<AppAuthService> apps;
    private final InternalCallers callers;
    private final ObjectProvider<TerminalAuthService> terminals;

    public GuardWebConfig(ObjectProvider<AppAuthService> apps, InternalCallers callers,
                          ObjectProvider<TerminalAuthService> terminals) {
        this.apps = apps;
        this.callers = callers;
        this.terminals = terminals;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver(apps, callers, terminals));
    }
}
