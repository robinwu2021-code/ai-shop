package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.api.InventoryException;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;

/**
 * ai-shop **内嵌**进销存的过渡期装配（ADR-046：商城切流 M8 完成后与内嵌一起删除）。
 *
 * <p><b>错误码在进程边界上翻译</b>：领域代码只抛 {@link InventoryException}；
 * 商城里几十处调用点、全局异常处理、前端文案认的都是 {@code BizException} 与商城码。
 * 在这里一次翻译，调用方一行不用改 —— 同一个翻译以后由独立形态的客户端承担。
 *
 * <p>用 Spring 自带的基础设施 Advisor 而不是 AspectJ 切面：ai-shop 没有引入 AspectJ，
 * 为一处翻译加一个织入框架不值。{@code ROLE_INFRASTRUCTURE} 让事务用的那个自动代理器也认得它；
 * 最高优先级让它包在事务外面 —— 先回滚、再翻译。
 */
@Configuration
public class InventoryEmbeddingConfiguration {

    static final String SERVICE_PACKAGE = "ai.neargo.inventory.domain.service.";

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor inventoryErrorTranslationAdvisor() {
        return errorTranslationAdvisor(SERVICE_PACKAGE);
    }

    /** 切点按包名前缀；测试用自己的前缀验证同一段翻译逻辑。 */
    static Advisor errorTranslationAdvisor(String packagePrefix) {
        MethodInterceptor translate = invocation -> {
            try {
                return invocation.proceed();
            } catch (InventoryException e) {
                throw InventoryErrors.toShop(e);
            }
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(translate);
        advisor.setPointcut(new StaticMethodMatcherPointcut() {
            @Override
            public ClassFilter getClassFilter() {
                return type -> type.getName().startsWith(packagePrefix);
            }

            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return true;
            }
        });
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }
}
