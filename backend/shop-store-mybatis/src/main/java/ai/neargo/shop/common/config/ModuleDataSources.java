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

    public static final String INVOICE_TX_MANAGER = "invoiceTxManager";
    public static final String INVOICE_DATA_SOURCE = "invoiceDataSource";
    public static final String INVOICE_SQL_SESSION_FACTORY = "invoiceSqlSessionFactory";
    public static final String PRINT_TX_MANAGER = "printTxManager";
    public static final String PRINT_DATA_SOURCE = "printDataSource";
    public static final String PRINT_SQL_SESSION_FACTORY = "printSqlSessionFactory";
    public static final String PLATFORM_TX_MANAGER = "platformTxManager";
    public static final String PLATFORM_DATA_SOURCE = "platformDataSource";
    public static final String PLATFORM_SQL_SESSION_FACTORY = "platformSqlSessionFactory";
    public static final String TRADE_TX_MANAGER = "tradeTxManager";
    public static final String TRADE_DATA_SOURCE = "tradeDataSource";
    public static final String TRADE_SQL_SESSION_FACTORY = "tradeSqlSessionFactory";
    public static final String INDUSTRY_TX_MANAGER = "industryTxManager";
    public static final String INDUSTRY_DATA_SOURCE = "industryDataSource";
    public static final String INDUSTRY_SQL_SESSION_FACTORY = "industrySqlSessionFactory";

    /** 守卫测试也读这份清单；常量名与这里的规则对不上会在那里变红。 */
    public static final List<String> MODULES = List.of(INVOICE, PRINT, PLATFORM, TRADE, INDUSTRY);

    private ModuleDataSources() {
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
