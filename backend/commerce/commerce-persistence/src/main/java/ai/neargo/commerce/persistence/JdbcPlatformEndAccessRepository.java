package ai.neargo.commerce.persistence;

import ai.neargo.commerce.persistence.platform.JdbcPlatformEndGrantStore;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.core.access.EndAccessService;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform authority uses the local store and its activated grant schedule. */
public final class JdbcPlatformEndAccessRepository implements EndAccessService.Repository {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcPlatformEndAccessRepository(DataSource platform) {
        dataSource = platform;
        jdbc = new JdbcTemplate(platform);
    }

    public void requireOuter(StoreRef store) {
        JdbcWriteGuard.require(dataSource, store.tenantNo());
    }

    public EndAccessService.State lockAndRead(StoreRef store, String end, String code, String client) {
        requireOuter(store);
        return JdbcPlatformEndGrantStore.access(jdbc, store, end, code, client);
    }
}
