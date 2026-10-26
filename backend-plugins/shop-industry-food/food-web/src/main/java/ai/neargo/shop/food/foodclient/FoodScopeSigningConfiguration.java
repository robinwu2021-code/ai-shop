package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.scope.FoodScopeCredentials;
import ai.neargo.shop.svc.InternalServiceInterceptor;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 独立部署时，宿主发往 FOOD 的<b>每一个</b>内部请求都签范围凭证（TDD-餐饮系统-内部调用签名凭证）。
 *
 * <p>以 {@link InternalServiceInterceptor} 声明，由 {@code InternalHttp} 挂到所有指向 FOOD 的客户端上 ——
 * 餐饮的客户端分散在七八个配置类里，逐个改调用点会漏，后加的也会漏。
 * 不带 profile 条件：api / ops 宿主与 worker 上的任务都会调 FOOD。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
public class FoodScopeSigningConfiguration {

    @Bean
    InternalServiceInterceptor foodScopeSigning(@Value("${shop.food.scope-key:}") String key) {
        return new InternalServiceInterceptor(ServiceName.FOOD,
                new FoodScopeSigningInterceptor(FoodScopeCredentials.key(key), () -> System.currentTimeMillis() / 1000));
    }
}
