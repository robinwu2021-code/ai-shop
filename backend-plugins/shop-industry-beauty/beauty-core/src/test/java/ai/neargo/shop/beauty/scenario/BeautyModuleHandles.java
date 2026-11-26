package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 测试上下文里的模块句柄（ADR-066）：美业的交易侧点名交易句柄，
 * 没有绑定数据源时它就是测试里手工注册的那几个主库 bean 的别名，和应用里一样。
 */
@Configuration(proxyBeanMethods = false)
@Import(ModuleDataSourceRegistrar.class)
public class BeautyModuleHandles {
}
