package ai.neargo.shop.config;

import ai.neargo.shop.common.config.CommonTableMapper;
import ai.neargo.shop.common.config.MemberMarketingTableMapper;
import ai.neargo.shop.common.config.ProductTableMapper;
import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.common.config.PlatformSqlMapper;
import ai.neargo.shop.common.config.PlatformTableMapper;
import ai.neargo.shop.common.config.TradeSqlMapper;
import ai.neargo.shop.common.config.TradeTableMapper;
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
@Import({ModuleDataSourceRegistrar.class, PlatformJdbcConfiguration.class})
@MapperScan(basePackages = "ai.neargo.shop.invoice.mapper", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.INVOICE_SQL_SESSION_FACTORY)
// 发票留在主库的那几张表（申请事件、邮件投递）：包名就是归属，见 invoice.legacy 的说明
@MapperScan(basePackages = "ai.neargo.shop.invoice.legacy", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "ai.neargo.shop.printing", markerInterface = BaseMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PRINT_SQL_SESSION_FACTORY)
// C 端账号包里只有账号、凭证、人档、小程序会话四个 Mapper 的表在平台基础库，按标记收；
// 同包的收货地址（门店交易库）、收藏与到店（会员营销库）、测试身份等仍由全局扫描绑主库。
// 会话与登录日志不走 Mapper，见 LoginAuditConfig#authJdbcClient
@MapperScan(basePackages = "ai.neargo.shop.user", markerInterface = PlatformTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
// 商户域里表在平台基础库的 Mapper：按标记收，不按包（同一个包里还有别的库的 Mapper）
@MapperScan(basePackages = "ai.neargo.shop.merchant.mapper", markerInterface = PlatformTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
@MapperScan(basePackages = "ai.neargo.shop.merchant.mapper", markerInterface = PlatformSqlMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
// 运营账号（新库 ops_staff）在平台基础库；同包其余 Mapper（版本、行业、审计等）的表不在平台库，仍由全局扫描绑主库
@MapperScan(basePackages = "ai.neargo.shop.platform.mapper", markerInterface = PlatformTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
// 门店交易库的 Mapper（ADR-068）：按标记收，不按包。交易相关的几个包里混着别的库的表，
// 「先留原表」的也留在主库，见 docs/technical/零售域-旧表新表对照.md
@MapperScan(basePackages = {"ai.neargo.shop.trade", "ai.neargo.shop.product", "ai.neargo.shop.fulfillment",
        "ai.neargo.shop.community", "ai.neargo.shop.marketing.group", "ai.neargo.shop.promotion.mapper",
        "ai.neargo.shop.merchant.mapper", "ai.neargo.shop.merchant.booking.mapper"}, markerInterface = TradeTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.TRADE_SQL_SESSION_FACTORY)
@MapperScan(basePackages = {"ai.neargo.shop.trade.order.mapper", "ai.neargo.shop.trade.mapper"},
        markerInterface = TradeSqlMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.TRADE_SQL_SESSION_FACTORY)
// 商品领域（TDD-商品领域迁到门店交易库）：表在门店交易库的收到商品句柄（部署时与交易句柄绑同一个数据源名）；
// 评价、专题、收藏的表在会员营销库，收到会员营销句柄
@MapperScan(basePackages = "ai.neargo.shop.product", markerInterface = ProductTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.PRODUCT_SQL_SESSION_FACTORY)
@MapperScan(basePackages = "ai.neargo.shop.product", markerInterface = MemberMarketingTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.MEMBER_MARKETING_SQL_SESSION_FACTORY)
// 基础能力库的 Mapper（TDD-基础能力库对齐 2a）：按标记收。先是权限，其余公共能力收敛后同样打标记
@MapperScan(basePackages = {"ai.neargo.shop.platform", "ai.neargo.shop.event"}, markerInterface = CommonTableMapper.class,
        sqlSessionFactoryRef = ModuleDataSources.COMMON_SQL_SESSION_FACTORY)
public class ModuleMapperScanConfig {
}
