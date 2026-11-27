package ai.neargo.shop.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 具名数据源不准指向旧库（TDD-商品领域迁到门店交易库 §2.2 L1）。 */
@DisplayName("数据源 · 指向旧库就拒绝启动")
class LegacyDatabaseGuardTest {
    private static final String[] LEGACY = ModuleDataSources.LEGACY_DATABASES.toArray(String[]::new);

    @Test
    void readsTheDatabaseNameFromJdbcUrls() {
        assertThat(ModuleDataSourceRegistrar.databaseOf("jdbc:mysql://192.168.3.61:3307/n_trade?useSSL=false"))
                .isEqualTo("n_trade");
        assertThat(ModuleDataSourceRegistrar.databaseOf("jdbc:mysql://db.svc.internal/ai_shop_ume")).isEqualTo("ai_shop_ume");
        assertThat(ModuleDataSourceRegistrar.databaseOf("jdbc:h2:mem:svc;MODE=MySQL")).isEmpty();
    }

    @Test
    void rejectsLegacyDatabasesIgnoringCase() {
        assertThatThrownBy(() -> ModuleDataSourceRegistrar.rejectLegacy("trade",
                "jdbc:mysql://192.168.3.61:3307/AI_SHOP_UME?useUnicode=true", LEGACY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.datasources.trade")
                .hasMessageContaining("旧库");
        assertThatThrownBy(() -> ModuleDataSourceRegistrar.rejectLegacy("inv",
                "jdbc:mysql://127.0.0.1:3307/ai_shop_ume_inv", LEGACY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsNewDatabasesAndInMemoryTestDatabases() {
        assertThatCode(() -> ModuleDataSourceRegistrar.rejectLegacy("trade",
                "jdbc:mysql://192.168.3.61:3307/n_trade?useSSL=false", LEGACY)).doesNotThrowAnyException();
        assertThatCode(() -> ModuleDataSourceRegistrar.rejectLegacy("service",
                "jdbc:h2:mem:svc;MODE=MySQL;DB_CLOSE_DELAY=-1", LEGACY)).doesNotThrowAnyException();
    }
}
