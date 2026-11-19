package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.gateway.core.discovery.NacosDiscoveryConfiguration;
import ai.neargo.shop.gateway.core.discovery.NacosRegistrar;
import ai.neargo.shop.gateway.core.discovery.NacosServiceLocator;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.ServiceLocator;
import ai.neargo.shop.svc.ServiceName;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 接入 Nacos：按服务名发现、回落到配置、登记本实例（ADR-062、TDD-网关层与统一权限 §2.24）。
 * Nacos 用替身，不连真的服务器。
 */
@DisplayName("网关 · Nacos 服务注册与发现")
class NacosDiscoveryTest {

    private static final String GROUP = "DEFAULT_GROUP";
    private final ServiceLocator configured = service ->
            ServiceName.PAY.equals(service) ? Optional.of("http://127.0.0.1:18093") : Optional.empty();

    private static Instance instance(String ip, int port, Map<String, String> metadata) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setMetadata(metadata);
        return instance;
    }

    @Test
    @DisplayName("★★★ 登记过的服务：Nacos 有健康实例就用它的地址；键大小写不敏感；实例声明了 https 就用 https")
    void mappedServiceResolvesFromNacos() throws Exception {
        NamingService naming = mock(NamingService.class);
        when(naming.selectOneHealthyInstance("near-pay", GROUP)).thenReturn(instance("192.168.3.166", 18093, Map.of()));
        // 环境变量注入的键是小写的
        NacosServiceLocator locator = new NacosServiceLocator(naming, configured, Map.of("pay", "near-pay"), GROUP);
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("http://192.168.3.166:18093");

        when(naming.selectOneHealthyInstance("near-pay", GROUP))
                .thenReturn(instance("pay.internal", 443, Map.of("scheme", "https")));
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("https://pay.internal:443");
    }

    @Test
    @DisplayName("★★★ 回落：Nacos 没有健康实例、或 Nacos 本身出错，都退回配置里的地址 —— 注册中心出问题不该让原本能调通的服务调不通")
    void fallsBackToConfiguredAddress() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosServiceLocator locator = new NacosServiceLocator(naming, configured, Map.of("PAY", "near-pay"), GROUP);

        when(naming.selectOneHealthyInstance("near-pay", GROUP)).thenReturn(null);
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("http://127.0.0.1:18093");

        doThrow(new NacosException(500, "no host to srv")).when(naming).selectOneHealthyInstance("near-pay", GROUP);
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("http://127.0.0.1:18093");

        doThrow(new IllegalStateException("client closed")).when(naming).selectOneHealthyInstance("near-pay", GROUP);
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("http://127.0.0.1:18093");
    }

    @Test
    @DisplayName("★★★ 没登记的服务完全不问 Nacos：与今天逐字相同；配置里也没有的仍是「没配」而不是报错")
    void unmappedServiceNeverAsksNacos() {
        NamingService naming = mock(NamingService.class);
        NacosServiceLocator locator = new NacosServiceLocator(naming, configured, Map.of("FOOD", "food-svc"), GROUP);
        assertThat(locator.baseUrlOf(ServiceName.PAY)).contains("http://127.0.0.1:18093");
        assertThat(locator.baseUrlOf(ServiceName.JOB)).isEmpty();
        verifyNoInteractions(naming);
    }

    @Test
    @DisplayName("★★ 登记本实例：就绪后登记一次临时实例并带上元数据；停机时摘除；登记失败不抛、停机时也不去摘")
    void registersOnReadyAndDeregistersOnClose() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosRegistrar registrar = new NacosRegistrar(naming, "ai-shop-ops", GROUP, "192.168.3.166", 18181,
                Map.of("face", "ops"));
        registrar.registerOnReady();
        ArgumentCaptor<Instance> captured = ArgumentCaptor.forClass(Instance.class);
        verify(naming, times(1)).registerInstance(eq("ai-shop-ops"), eq(GROUP), captured.capture());
        assertThat(captured.getValue().getIp()).isEqualTo("192.168.3.166");
        assertThat(captured.getValue().getPort()).isEqualTo(18181);
        assertThat(captured.getValue().isEphemeral()).isTrue();
        assertThat(captured.getValue().getMetadata()).containsEntry("face", "ops");
        assertThat(registrar.registered()).isTrue();
        registrar.deregisterOnClose();
        verify(naming).deregisterInstance("ai-shop-ops", GROUP, "192.168.3.166", 18181);

        NamingService down = mock(NamingService.class);
        doThrow(new NacosException(403, "unknown user")).when(down).registerInstance(anyString(), anyString(), any());
        NacosRegistrar failing = new NacosRegistrar(down, "ai-shop-ops", GROUP, "192.168.3.166", 18181, Map.of());
        failing.registerOnReady();
        assertThat(failing.registered()).isFalse();
        failing.deregisterOnClose();
        verify(down, never()).deregisterInstance(anyString(), anyString(), anyString(), anyInt());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConfigServiceLocator.class)
    static class Base {
    }

    @Test
    @DisplayName("★★★ 默认关闭：不装任何 Nacos 的东西，服务定位仍是读配置的那一个；开了但没配地址则拒绝启动")
    void offByDefault() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Base.class, NacosDiscoveryConfiguration.class)
                .withPropertyValues("shop.services.targets.PAY=http://127.0.0.1:18093");
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(NamingService.class).doesNotHaveBean(NacosRegistrar.class);
            assertThat(context.getBean(ServiceLocator.class)).isInstanceOf(ConfigServiceLocator.class);
            assertThat(context.getBean(ServiceLocator.class).baseUrlOf(ServiceName.PAY))
                    .contains("http://127.0.0.1:18093");
        });
        runner.withPropertyValues("shop.nacos.enabled=true").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("shop.nacos.server-addr"));
    }
}
