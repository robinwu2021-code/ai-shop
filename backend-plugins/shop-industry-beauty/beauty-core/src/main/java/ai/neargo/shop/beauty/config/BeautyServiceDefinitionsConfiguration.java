package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.service.impl.ServiceDefinitions;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 美业读写服务定义的唯一入口（TDD-美业域迁移新库 §2.8 P5）。用到它的配置（发布、目录、预约、开单、提交）各自 import，
 * 只注册一次。端口缺席时（只部署门店政策）延迟到第一次调用才报错。
 */
@Configuration(proxyBeanMethods = false)
public class BeautyServiceDefinitionsConfiguration {
    @Bean
    ServiceDefinitions beautyServiceDefinitions(ObjectProvider<ServiceDefinitionPort> port, ObjectMapper json) {
        return new ServiceDefinitions(port, json);
    }
}
