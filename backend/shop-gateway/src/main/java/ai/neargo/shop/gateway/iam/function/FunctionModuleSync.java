package ai.neargo.shop.gateway.iam.function;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.config.GatewayEndSwitch;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.function.ModuleOwnership;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后，把「功能点归哪个模块」回填进运营端的功能点表（TDD-网关层与统一权限 §2.6；开发计划 F 第二步）。
 *
 * <p>只写归属这一个源码事实：按权限码算，与本次部署装了哪些模块无关，所以多个实例、多套部署写出来的值相同。
 * 内容没变就一行不写。失败只告警、不影响启动 —— 归属没填上的点按「可用」处理，与今天一样。
 */
@Component
public class FunctionModuleSync {

    private static final Logger log = LoggerFactory.getLogger(FunctionModuleSync.class);

    private record Row(long id, String permCode, String moduleCode) {
    }

    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<GatewayEndRuntimes> runtimes;
    private final Environment environment;

    public FunctionModuleSync(ObjectProvider<DataSource> dataSource, ObjectProvider<GatewayEndRuntimes> runtimes,
                              Environment environment) {
        this.dataSource = dataSource;
        this.runtimes = runtimes;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnReady() {
        if (GatewayEndSwitch.resolve(environment, "ops", "ops") != GatewayEndSwitch.State.ON) {
            return;
        }
        try {
            int updated = sync();
            if (updated > 0) {
                log.info("功能点的模块归属已回填 {} 行", updated);
            }
        } catch (RuntimeException e) {
            log.warn("功能点的模块归属回填失败（不影响菜单与判权）：{}", e.toString());
        }
    }

    /** 返回改动的行数。 */
    public int sync() {
        JdbcClient jdbc = jdbc();
        if (jdbc == null) {
            return 0;
        }
        List<Row> rows = jdbc.sql("SELECT id, perm_code, module_code FROM sys_function_point")
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("perm_code"), rs.getString("module_code")))
                .list();
        int updated = 0;
        for (Row row : rows) {
            String module = row.permCode() == null || row.permCode().isBlank() ? null
                    : ModuleOwnership.moduleOf(Realm.OPERATOR, row.permCode());
            if (!Objects.equals(module, row.moduleCode())) {
                jdbc.sql("UPDATE sys_function_point SET module_code = ? WHERE id = ?").params(module, row.id()).update();
                updated++;
            }
        }
        return updated;
    }

    private JdbcClient jdbc() {
        GatewayEndRuntimes ends = runtimes.getIfAvailable();
        DataSource main = dataSource.getIfAvailable();
        if (ends != null) {
            var own = ends.jdbc(Realm.OPERATOR);
            if (own.isPresent()) {
                return own.get();
            }
        }
        return main == null ? null : JdbcClient.create(main);
    }
}
