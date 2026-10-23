package ai.neargo.shop.food.fixture.core;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import org.springframework.stereotype.Component;

/** 测试专用组件：模拟宿主直接扫描，不能随生产 jar 交付。 */
public final class DomainProbes {
    private DomainProbes() {
    }

    @Component
    @ConditionalOnFoodEnabled
    public static class ServiceProbe {
    }

    @Component
    @ConditionalOnFoodEnabled
    public static class MapperProbe {
    }

    @Component
    @ConditionalOnFoodEnabled
    public static class JobProbe {
    }
}
