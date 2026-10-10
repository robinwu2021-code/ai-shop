package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>进销存关着的时候，服务要能起来。</b>
 *
 * <h2>这条守卫是被一次线上事故催出来的（2026-08-27）</h2>
 * 部署之后服务反复重启，日志里是：
 * <pre>
 * Parameter 0 of constructor in InventorySnapshotServiceImpl
 *   required a bean of type 'InventoryMappers$LedgerMapper' that could not be found
 * </pre>
 * 原因：{@code InventorySnapshotServiceImpl} 与 {@code OpenApiCredentialServiceImpl}
 * <b>漏挂了 {@code @ConditionalOnInventory}</b>。进销存的 Mapper 只在开关打开时注册，
 * 而这两个 bean 不管开关照样被实例化 —— 于是注入不到，整个上下文起不来。
 *
 * <h2>为什么此前全绿</h2>
 * 因为**测试从来只跑开着的那一半**：`application-h2db.yml` 里
 * {@code shop.inventory.enabled: true}。关着的那条分支一次都没有被执行过，
 * 而线上恰恰跑在那一条上。
 *
 * <p>「一个功能默认关闭」意味着<b>关闭态才是生产的常态</b>，
 * 它比打开态更需要一条守卫 —— 而它偏偏是最容易忘记测的那一态。
 */
@SpringBootTest
@ActiveProfiles("test")
/*
 * **自己的内存库。** 这是第三个上下文（test / test+openapi / 这一个），
 * 而 H2 是 `DB_CLOSE_DELAY=-1` 的共享库 —— 每多一个上下文就多跑一遍
 * `schema-test.sql`，在同一个库上撞 `sys_industry` 主键，
 * 而那一次失败会拖垮之后所有上下文：**单独跑绿、一起跑全红**，
 * 且报错指向的类与真因毫不相干。
 */
@TestPropertySource(properties = {
        "shop.inventory.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:shop_invoff;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
class InventoryDisabledContextTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★★★ 进销存开关关着时上下文照样起得来 —— 线上跑的就是这一态")
    void contextStartsWithInventoryDisabled() {
        // 能注入 ApplicationContext 就说明上下文起来了；这一条的价值全在「它跑过」
        assertThat(ctx).isNotNull();

        /*
         * 顺带钉住：关着的时候本域的 bean **一个都不该在**。
         * 留下任何一个，它迟早会去要一个不存在的 Mapper —— 那正是这次的形状。
         */
        assertThat(ctx.getBeanNamesForType(
                ai.neargo.shop.inventory.service.StockQueryService.class))
                .as("进销存关着时不该有本域的 Service").isEmpty();
        assertThat(ctx.getBeanNamesForType(
                ai.neargo.shop.inventory.service.InventorySnapshotService.class))
                .as("日快照也不例外 —— 这次就是它把上下文拖垮的").isEmpty();
        assertThat(ctx.getBeanNamesForType(
                ai.neargo.shop.inventory.service.OpenApiCredentialService.class))
                .as("凭证服务同上").isEmpty();
    }
}
