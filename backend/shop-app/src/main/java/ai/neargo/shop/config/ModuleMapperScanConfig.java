package ai.neargo.shop.config;

import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import ai.neargo.shop.common.config.ModuleDataSources;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 可选数据源的模块各自的 Mapper 扫描（ADR-066）。
 *
 * <p>两头夹，和进销存、支付同一个道理：{@link MybatisPlusConfig} 的全局扫描把这两个包排除，
 * 这里按包绑到模块自己的工厂句柄。少任何一头，Mapper 都会落回主库工厂 ——
 * 模块没绑定数据源时看不出任何区别，绑定那天才表现为「表不存在」。
 *
 * <p>发票模块可以不打进包（{@code -Dinvoice.exclude}），扫描一个不存在的包不会报错。
 */
@Configuration(proxyBeanMethods = false)
@Import(ModuleDataSourceRegistrar.class)
@MapperScan(basePackages = "ai.neargo.shop.invoice.mapper", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.INVOICE_SQL_SESSION_FACTORY)
// 发票留在主库的那几张表（申请事件、邮件投递）：包名就是归属，见 invoice.legacy 的说明
@MapperScan(basePackages = "ai.neargo.shop.invoice.legacy", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "ai.neargo.shop.printing", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PRINT_SQL_SESSION_FACTORY)
// C 端账号域（登录与账号的一部分）在平台基础库；会话与登录日志不走 Mapper，见 LoginAuditConfig#authJdbcClient
@MapperScan(basePackages = "ai.neargo.shop.user", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
public class ModuleMapperScanConfig {
}
