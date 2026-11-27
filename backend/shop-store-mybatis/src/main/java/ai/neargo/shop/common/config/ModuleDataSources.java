package ai.neargo.shop.common.config;

import java.util.List;

/**
 * 可以在配置里选数据源的业务模块，以及它们的句柄叫什么（ADR-066）。
 *
 * <p>业务代码只认句柄，不认库：模块里的 Mapper 扫描点名 {@code <模块>SqlSessionFactory}，
 * 事务点名 {@code <模块>TxManager}。模块没有绑定数据源时，这三个名字是主库那三个 bean 的别名，
 * 行为与没有这套机制时一样。新模块要支持选库，在 {@link #MODULES} 里加一个名字。
 */
public final class ModuleDataSources {

    /** 发票模块（{@code ai.neargo.shop.invoice}）。 */
    public static final String INVOICE = "invoice";
    /** 打印模块（{@code ai.neargo.shop.printing}）。 */
    public static final String PRINT = "print";
    /**
     * 平台基础库上的模块：登录与账号、商户与门店、门店设置与设备、员工与权限。
     * 这几个模块同在一个库、互相之间有同事务的写，所以共用一个句柄，不各开一个。
     */
    public static final String PLATFORM = "platform";
    /**
     * 支付商户库上的模块：支付开通与进件（支付商户、渠道商户号、开通的产品、进件）。
     * 没绑定时落到支付域现有的那一组（{@code payDataSource} 等），不是主库 —— 见 {@link #unboundTarget}。
     */
    public static final String PAY_MERCHANT = "payMerchant";
    /**
     * 门店交易库上的交易模块：商品、门店目录与可售、收银与下单、订单与售后、履约、渠道、预约与资源。
     * 这些子模块同在一个库、下单等事务里互相写，共用一个句柄。哪些 Mapper 归它，看 {@link TradeTableMapper}。
     */
    public static final String TRADE = "trade";
    /**
     * 行业库上的行业模块：餐饮的桌台、工位、出品单、厨显、人头权益、宴会、排队、寄存、服务交接，
     * 美业的服务执行，以及两个行业共用的门店经营策略、提成规则（ADR-068）。代码在行业插件里，
     * 各插件的持久化配置按 {@link IndustryTableMapper} 收到这个句柄上。
     */
    public static final String INDUSTRY = "industry";

    /**
     * 基础能力库上的公共能力：权限（运营端角色、功能点、接口资源），以及随后收进来的审计、设置、发号、字典、审批、开放应用
     * （TDD-基础能力库对齐 Q2：独立句柄，未绑定时指向主库）。哪些 Mapper 归它，看 {@link CommonTableMapper}。
     */
    public static final String COMMON = "common";

    public static final String INVOICE_TX_MANAGER = "invoiceTxManager";
    public static final String INVOICE_DATA_SOURCE = "invoiceDataSource";
    public static final String INVOICE_SQL_SESSION_FACTORY = "invoiceSqlSessionFactory";
    public static final String PRINT_TX_MANAGER = "printTxManager";
    public static final String PRINT_DATA_SOURCE = "printDataSource";
    public static final String PRINT_SQL_SESSION_FACTORY = "printSqlSessionFactory";
    public static final String PAY_MERCHANT_TX_MANAGER = "payMerchantTxManager";
    public static final String PAY_MERCHANT_DATA_SOURCE = "payMerchantDataSource";
    public static final String PAY_MERCHANT_SQL_SESSION_FACTORY = "payMerchantSqlSessionFactory";
    public static final String PLATFORM_TX_MANAGER = "platformTxManager";
    public static final String PLATFORM_DATA_SOURCE = "platformDataSource";
    public static final String PLATFORM_SQL_SESSION_FACTORY = "platformSqlSessionFactory";
    public static final String TRADE_TX_MANAGER = "tradeTxManager";
    public static final String TRADE_DATA_SOURCE = "tradeDataSource";
    public static final String TRADE_SQL_SESSION_FACTORY = "tradeSqlSessionFactory";
    public static final String INDUSTRY_TX_MANAGER = "industryTxManager";
    public static final String INDUSTRY_DATA_SOURCE = "industryDataSource";
    public static final String INDUSTRY_SQL_SESSION_FACTORY = "industrySqlSessionFactory";
    public static final String COMMON_TX_MANAGER = "commonTxManager";
    public static final String COMMON_DATA_SOURCE = "commonDataSource";
    public static final String COMMON_SQL_SESSION_FACTORY = "commonSqlSessionFactory";

    /** 守卫测试也读这份清单；常量名与这里的规则对不上会在那里变红。 */
    public static final List<String> MODULES = List.of(INVOICE, PRINT, PLATFORM, PAY_MERCHANT, TRADE, INDUSTRY, COMMON);

    private ModuleDataSources() {
    }

    /**
     * 模块没有绑定数据源时，句柄指向谁：{数据源, 工厂, 事务管理器} 三个 bean 名。
     *
     * <p>绝大多数模块是主库。支付域例外：它早就有自己的连接池与事务管理器（同一个库、与平台事务隔离），
     * 从支付域里分出来的模块没绑定时必须仍在支付的事务里，落到主库会让支付的写悄悄并进平台事务。
     */
    public static String[] unboundTarget(String module) {
        return PAY_MERCHANT.equals(module)
                ? new String[] {"payDataSource", "paySqlSessionFactory", "payTxManager"}
                : new String[] {"dataSource", "sqlSessionFactory", "transactionManager"};
    }

    public static String dataSource(String name) {
        return name + "DataSource";
    }

    public static String sqlSessionFactory(String name) {
        return name + "SqlSessionFactory";
    }

    public static String txManager(String name) {
        return name + "TxManager";
    }
}
