package ai.neargo.shop.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 发票域（ADR-063）的**独立迁移序列** {@code db/invoice}（在 invoice-domain 里，跟着模块走），
 * 历史表 {@code ivc_flyway_history}，迁移号从 V1 起 —— 主序列有十几个会话并行抢号，本分支与 main 已差几十号。
 *
 * <p>同库同数据源同事务管理器：这一步只是让迁移跟着模块走，不切库。写法与 {@code PayDataSourceConfig#payFlyway}
 * 相同：返回类型不是 {@code Flyway}（否则平台迁移整体不跑，{@code ArchitectureTest#noBeanMayExposeFlyway}），
 * 排在平台迁移之后（V2 要改平台的 {@code mch_role}），平台迁移没启用的切片测试里跟着不跑。
 */
@Configuration
public class InvoiceSchemaConfig {

    static final String HISTORY_TABLE = "ivc_flyway_history";

    /** 发票迁移跑完的凭据。 */
    public record InvoiceMigrated(String historyTable, int applied) {}

    @Bean
    InvoiceMigrated invoiceFlyway(@Qualifier("dataSource") DataSource ds, ObjectProvider<FlywayMigrationInitializer> platformFlyway,
            @Value("${shop.invoice.flyway.enabled:true}") boolean enabled,
            @Value("${shop.invoice.flyway.locations:classpath:db/invoice}") String locations) {
        if (!enabled || platformFlyway.getIfAvailable() == null) return new InvoiceMigrated(HISTORY_TABLE, 0);
        return new InvoiceMigrated(HISTORY_TABLE, migrate(ds, locations));
    }

    static int migrate(DataSource ds, String locations) {
        return Flyway.configure()
                .dataSource(ds)
                .locations(locations.split(","))
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate().migrationsExecuted;
    }
}
