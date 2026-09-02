package ai.neargo.shop.arch;

import ai.neargo.shop.payclient.OpsFeeRuleAppService;
import ai.neargo.shop.payclient.OpsPayChannelAppService;
import ai.neargo.shop.payclient.OpsSettleInvoiceAppService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主应用在 <b>生产那一半</b> 的装配（S20）。
 *
 * <h2>为什么单独开一个上下文</h2>
 * pay 域的实现按 {@code shop.pay.deployment} 二选一：
 * {@code embedded}（本地实现，{@code matchIfMissing = true}）
 * 与 {@code standalone}（远程实现）。
 *
 * <p><b>1700 条测试跑的全是 embedded 那一半</b> —— 因为不设这个开关时
 * {@code matchIfMissing} 让它默认成立。而<b>生产跑的是 standalone</b>。
 *
 * <p>2026-09-02 上线即挂过一次，正是这个形状：一个 app service 只挂了
 * {@code embedded} 条件，standalone 下那个 bean 根本不存在，
 * 容器起不来 —— 而本地全绿。当时靠回滚收场。
 *
 * <p>事后加的 {@link PayDeploymentModePairingTest} 是<b>静态扫源码文本</b>：
 * 它能发现「有 embedded 没有 standalone」，但发现不了
 * 「standalone 的实现自己缺一个构造依赖」或「某个配置类只在一半里提供 bean」。
 * <b>那种问题只有真的把上下文装起来才知道</b>。
 *
 * <p>{@code scripts/smoke-boot.sh} 也在做这件事，但它要先打包、只在部署前跑。
 * 这一条搬进 {@code mvn test}，于是每次都跑。
 */
/*
 * ⚠️ **必须换一个 H2 库名。**
 *
 * 加了 properties 就是另一个上下文缓存键，Spring 会重新装配一次 ——
 * 而那一次会**把 schema-test.sql 再跑一遍**。默认库名下那些种子已经在了，
 * 于是第 173 条 INSERT 撞主键，整个上下文起不来。
 *
 * 症状很误导：这个类<b>单独跑全绿、全量跑红</b>，而报错是
 * 「sys_industry 主键冲突」—— 与「standalone 装配」四个字毫无关系。
 *
 * 三张库都要换：主库、库存库、pay 那一份（后者跟着主库走）。
 */
@SpringBootTest(properties = {
        "shop.pay.deployment=standalone",
        "spring.datasource.url=jdbc:h2:mem:shopstandalone;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.datasource.url=jdbc:h2:mem:invstandalone;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
class PayStandaloneAssemblyTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★★★ standalone 下上下文装得起来 —— 生产跑的就是这一半，而测试默认跑另一半")
    void contextLoadsInStandaloneMode() {
        assertThat(ctx.getEnvironment().getProperty("shop.pay.deployment"))
                .as("开关没生效的话这一条测的还是 embedded —— 与其余 1700 条一模一样")
                .isEqualTo("standalone");
        assertThat(ctx.getBeanDefinitionCount()).isGreaterThan(100);
    }

    @Test
    @DisplayName("★★★ 三个 ops 门面在 standalone 下都有实现 —— 缺一个就是上线即挂")
    void opsFacadesResolveToRemoteImpls() {
        /*
         * 断言的是**拿得到 bean**，不是拿到哪一个实现类：
         * 钉死实现类的话，将来某个门面改成两边共用一个实现，这一条会
         * 因为一次正确的重构而变红 —— 而它要防的是「一个都没有」。
         */
        for (Class<?> facade : new Class<?>[]{
                OpsPayChannelAppService.class,
                OpsFeeRuleAppService.class,
                OpsSettleInvoiceAppService.class}) {
            assertThat(ctx.getBeanNamesForType(facade))
                    .as(facade.getSimpleName() + " 在 standalone 下一个实现都没有。"
                            + "生产的 shop.pay.deployment 就是 standalone —— "
                            + "这正是 2026-09-02 上线即挂那次的形状")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("★★★ 每个门面只有一个实现 —— 两个都装上时容器起不来，且报的是别的错")
    void noDuplicateImplementations() {
        for (Class<?> facade : new Class<?>[]{
                OpsPayChannelAppService.class,
                OpsFeeRuleAppService.class,
                OpsSettleInvoiceAppService.class}) {
            assertThat(ctx.getBeanNamesForType(facade))
                    .as(facade.getSimpleName() + " 有不止一个实现 —— "
                            + "两半的条件写重叠了（比如 standalone 那个忘了排除 matchIfMissing）")
                    .hasSize(1);
        }
    }
}
