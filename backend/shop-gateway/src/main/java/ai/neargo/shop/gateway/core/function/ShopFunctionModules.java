package ai.neargo.shop.gateway.core.function;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仓库内各模块的功能点声明（TDD-网关层与统一权限 §2.6）。
 *
 * <p>一个模块一个 bean，装配条件与该模块自己的开关相同：模块没开，声明就不在，它的功能点在登记表里不存在。
 * 声明集中写在网关而不是散在各模块里：各模块不依赖网关（依赖方向是网关 → 模块），没有地方放这个接口的实现。
 */
@Configuration(proxyBeanMethods = false)
public class ShopFunctionModules {

    /** 基础业务层的零售与运营平台本身：始终在。 */
    @Bean
    FunctionModule retailFunctions() {
        return module(ModuleOwnership.RETAIL);
    }

    @Bean
    FunctionModule platformFunctions() {
        return module(ModuleOwnership.PLATFORM);
    }

    /** 支付：同进程或独立部署，出口都在本工程，所以始终在。 */
    @Bean
    FunctionModule payFunctions() {
        return module(ModuleOwnership.PAY);
    }

    /** 服务业（预约、服务项目）。 */
    @Bean
    FunctionModule serviceFunctions() {
        return module(ModuleOwnership.SERVICE);
    }

    @Bean
    FunctionModule elecFunctions() {
        return module(ModuleOwnership.ELEC);
    }

    @Bean
    @ConditionalOnProperty(name = "shop.inventory.enabled", havingValue = "true")
    FunctionModule inventoryFunctions() {
        return module(ModuleOwnership.INVENTORY);
    }

    @Bean
    @ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
    FunctionModule printFunctions() {
        return module(ModuleOwnership.PRINT);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
    FunctionModule commerceFunctions() {
        return module(ModuleOwnership.COMMERCE);
    }

    @Bean
    @ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
    FunctionModule jobFunctions() {
        return module(ModuleOwnership.JOB);
    }

    private static FunctionModule module(String code) {
        List<FunctionPoint> points = List.copyOf(ModuleOwnership.pointsOf(code));
        return new FunctionModule() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public List<FunctionPoint> points() {
                return points;
            }
        };
    }
}
