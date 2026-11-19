package ai.neargo.shop.gateway.core.resource;

import ai.neargo.shop.gateway.core.config.GatewayEndSwitch;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后，把运营端、B 端与 C 端扫出来的链接同步进接口资源表（TDD-网关层与统一权限 §2.13；开发计划 M4b）。
 *
 * <p>等到应用就绪才做：那时 Spring MVC 的处理方法已全部登记。
 * 同步失败只告警、不影响启动 —— 判权读的是进程内的索引，库里这份是给人看的副本。
 * 一条链接都没扫到时不同步：那多半是非 Web 进程，照着空清单同步会把整张表标成过期。
 */
@Component
public class ResourceSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(ResourceSyncRunner.class);
    /** 要同步的一条链：库里的端名、地址前缀，以及它属于哪一端（开关名、部署面）。 */
    private record Chain(String endCode, String prefix, String end, String profile) {
    }

    private static final List<Chain> CHAINS = List.of(new Chain("ops", "/ops", "ops", "ops"),
            new Chain("pay-ops", "/pay-ops", "ops", "ops"), new Chain("biz", "/biz", "biz", "api"),
            new Chain("mp", "/mp", "mp", "api"));

    private final ResourceIndexes indexes;
    private final ObjectProvider<ResourceStore> store;
    private final ObjectProvider<BuildProperties> build;
    private final Environment environment;
    private final ObjectProvider<ai.neargo.shop.gateway.core.integration.ManifestSeedLoader> seeds;

    public ResourceSyncRunner(ResourceIndexes indexes, ObjectProvider<ResourceStore> store,
                              ObjectProvider<BuildProperties> build, Environment environment,
                              ObjectProvider<ai.neargo.shop.gateway.core.integration.ManifestSeedLoader> seeds) {
        this.seeds = seeds;
        this.indexes = indexes;
        this.store = store;
        this.build = build;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnReady() {
        ResourceStore target = store.getIfAvailable();
        if (target == null) {
            return;
        }
        // 先写微服务清单的种子（只插入还不存在的行），再同步本进程入口
        seeds.ifAvailable(ai.neargo.shop.gateway.core.integration.ManifestSeedLoader::seedAll);
        BuildProperties info = build.getIfAvailable();
        String version = info == null ? null : info.getVersion();
        CHAINS.forEach(chain -> {
            String end = chain.endCode();
            String prefix = chain.prefix();
            // 这一端没在本进程里开，或者显式关了同步，就不碰它的行：照着空清单同步会把别的实例写的行标成过期
            if (GatewayEndSwitch.resolve(environment, chain.end(), chain.profile()) != GatewayEndSwitch.State.ON
                    || !environment.getProperty("shop.gateway." + chain.end() + ".resource-sync", Boolean.class,
                            true)) {
                return;
            }
            try {
                // 只同步本进程入口；录入的微服务链接本来就在库里
                List<ApiResource> scanned = indexes.of(List.of(prefix)).all().stream()
                        .filter(resource -> !resource.remote()).toList();
                if (scanned.isEmpty()) {
                    return;
                }
                ResourceStore.SyncResult result = target.syncScanned(end, scanned, version);
                if (!result.unchanged()) {
                    log.info("接口资源已同步 end={} 共 {} 条：新增 {}，更新 {}，标为过期 {}",
                            end, scanned.size(), result.inserted(), result.updated(), result.markedStale());
                }
            } catch (RuntimeException e) {
                log.warn("接口资源同步失败（不影响判权）end={} 异常={}", end, e.toString());
            }
        });
    }
}
