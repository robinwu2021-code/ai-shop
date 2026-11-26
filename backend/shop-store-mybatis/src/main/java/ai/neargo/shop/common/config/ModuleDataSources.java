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

    public static final String INVOICE_TX_MANAGER = "invoiceTxManager";
    public static final String INVOICE_DATA_SOURCE = "invoiceDataSource";
    public static final String INVOICE_SQL_SESSION_FACTORY = "invoiceSqlSessionFactory";
    public static final String PRINT_TX_MANAGER = "printTxManager";
    public static final String PRINT_DATA_SOURCE = "printDataSource";
    public static final String PRINT_SQL_SESSION_FACTORY = "printSqlSessionFactory";

    /** 守卫测试也读这份清单；常量名与这里的规则对不上会在那里变红。 */
    public static final List<String> MODULES = List.of(INVOICE, PRINT);

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
