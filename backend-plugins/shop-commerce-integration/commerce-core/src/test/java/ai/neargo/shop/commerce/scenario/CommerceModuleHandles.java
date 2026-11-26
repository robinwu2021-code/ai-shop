package ai.neargo.shop.commerce.scenario;

import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 测试上下文里的模块句柄（ADR-066）：渠道主流程的 Mapper 与事务点名交易句柄，
 * 没有绑定数据源时它就是主库的别名，和应用里一样。
 */
@Configuration(proxyBeanMethods = false)
@Import(ModuleDataSourceRegistrar.class)
public class CommerceModuleHandles {
}
