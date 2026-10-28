package ai.neargo.shop.config;

import ai.neargo.shop.auth.CallSourceInterceptor;
import ai.neargo.shop.spi.pay.CallSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部调用的来源（ADR-045 G9；开发计划 M2.1）：本服务名写进 {@link CallSources}，每个 HTTP 请求绑成来源。
 * 定时任务与 Outbox 消费由各自的执行器绑定（{@code InProcessJobConfig} · {@code OutboxDispatcher}）。
 */
@Configuration
public class CallSourceConfig implements WebMvcConfigurer {

    public CallSourceConfig(@Value("${spring.application.name:shop-app}") String service) {
        CallSources.configure(service);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部调用的来源由 InternalCallFilter 从上游接力（M2.6），这里不能拿本次请求自己的信息顶掉
        registry.addInterceptor(new CallSourceInterceptor()).excludePathPatterns("/internal/**");
    }
}
