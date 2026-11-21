package ai.neargo.shop.gateway.core.discovery;

import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.ServiceLocator;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 接入 Nacos 的装配（ADR-062、TDD-网关层与统一权限 §2.24）。{@code shop.nacos.enabled=true} 才生效。
 *
 * <p>用 Nacos 的原生客户端，不引入 Spring Cloud：本仓库的服务地址只有 {@link ServiceLocator} 这一个出口，
 * 接一个注册中心就是给它加一个实现；登记本实例是启动与停机时各调一次接口。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.nacos.enabled", havingValue = "true")
@EnableConfigurationProperties(NacosDiscoveryProperties.class)
public class NacosDiscoveryConfiguration {

    @Bean(destroyMethod = "shutDown")
    NamingService nacosNamingService(NacosDiscoveryProperties properties) throws NacosException {
        if (properties.getServerAddr().isBlank()) {
            throw new IllegalStateException("shop.nacos.enabled=true 但没有配 shop.nacos.server-addr");
        }
        Properties client = new Properties();
        client.setProperty(PropertyKeyConst.SERVER_ADDR, properties.getServerAddr());
        if (!properties.getNamespace().isBlank()) {
            client.setProperty(PropertyKeyConst.NAMESPACE, properties.getNamespace());
        }
        if (!properties.getUsername().isBlank()) {
            client.setProperty(PropertyKeyConst.USERNAME, properties.getUsername());
            client.setProperty(PropertyKeyConst.PASSWORD, properties.getPassword());
        }
        return NacosFactory.createNamingService(client);
    }

    /** 调用方注入的 {@link ServiceLocator} 换成这一个；读配置的那个仍在，作为回落。 */
    @Bean
    @Primary
    ServiceLocator nacosServiceLocator(NamingService naming, ConfigServiceLocator configured,
                                       NacosDiscoveryProperties properties) {
        return new NacosServiceLocator(naming, configured, properties.getDiscovery().getServices(),
                properties.getGroup());
    }

    @Bean
    @ConditionalOnProperty(name = "shop.nacos.register.enabled", havingValue = "true", matchIfMissing = true)
    NacosRegistrar nacosRegistrar(NamingService naming, NacosDiscoveryProperties properties, Environment environment,
                                  ObjectProvider<BuildProperties> build) {
        NacosDiscoveryProperties.Register register = properties.getRegister();
        String face = faceOf(environment);
        String serviceName = register.getServiceName().isBlank() ? "ai-shop-" + face : register.getServiceName();
        NamingUtils.checkServiceNameFormat(properties.getGroup() + "@@" + serviceName);
        int port = register.getPort() > 0 ? register.getPort()
                : environment.getProperty("server.port", Integer.class, 8080);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("face", face);
        metadata.put("scheme", "http");
        BuildProperties info = build.getIfAvailable();
        if (info != null && info.getTime() != null) {
            metadata.put("buildTime", info.getTime().toString());
        }
        metadata.putAll(register.getMetadata());
        return new NacosRegistrar(naming, serviceName, properties.getGroup(), ipOf(register, environment), port,
                metadata);
    }

    /** 部署面：运营端是 ops，C 端与 B 端是 api；两个都在（本机开发、测试）记成 all。 */
    private static String faceOf(Environment environment) {
        boolean ops = environment.matchesProfiles("ops");
        boolean api = environment.matchesProfiles("api");
        return ops && api ? "all" : ops ? "ops" : api ? "api" : "app";
    }

    private static String ipOf(NacosDiscoveryProperties.Register register, Environment environment) {
        if (!register.getIp().isBlank()) {
            return register.getIp();
        }
        String bound = environment.getProperty("server.address", "");
        if (!bound.isBlank() && !"0.0.0.0".equals(bound)) {
            return bound;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}
