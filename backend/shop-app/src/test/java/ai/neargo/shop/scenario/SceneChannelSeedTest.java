package ai.neargo.shop.scenario;

import ai.neargo.shop.message.NotificationConsumer;
import ai.neargo.shop.message.NotifyScene;
import ai.neargo.shop.message.entity.MsgSceneChannel;
import ai.neargo.shop.message.mapper.MessageMappers.SceneChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景×通道种子守卫（设计：多渠道推送与运营端触达配置 · 需求 1）。
 *
 * <p>路由「查不到 = 关」是保守兜底，不是许可：一个 {@link NotificationConsumer} 会处理、
 * 却没落种子的场景，会静默地一条外发通道都不走 —— 站内信还在，但到货推送/微信全没了，
 * 而且没有任何报错。这条守卫把「漏配」从线上事故提前成测试期红灯：
 *
 * <ul>
 *   <li>每个处理中的场景都必须有 INAPP 行且开启（站内信是事实记录，配置表也得显式记着）；</li>
 *   <li>每个场景至少还有一条加速通道行（WXSUB/PUSH），否则这个场景搬进配置表毫无意义。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SceneChannelSeedTest {

    @Autowired
    private SceneChannelMapper mapper;

    @Test
    @DisplayName("每个处理中的场景都落了种子：INAPP 开启 + 至少一条加速通道")
    void everyHandledSceneIsSeeded() {
        for (String scene : NotificationConsumer.handledScenes()) {
            List<MsgSceneChannel> rows = mapper.selectList(
                    Wrappers.<MsgSceneChannel>lambdaQuery()
                            .eq(MsgSceneChannel::getSceneCode, scene));
            assertThat(rows)
                    .as("场景 %s 没有任何场景×通道配置 —— 会静默不外发，请在迁移里补种子", scene)
                    .isNotEmpty();

            assertThat(rows)
                    .as("场景 %s 缺 INAPP 行或未开启 —— 站内信是必达事实记录", scene)
                    .anyMatch(r -> MsgSceneChannel.CH_INAPP.equals(r.getChannel())
                            && Boolean.TRUE.equals(r.getEnabled()));

            assertThat(rows)
                    .as("场景 %s 只有 INAPP，没有任何加速通道行（WXSUB/PUSH）—— 搬进配置表没意义", scene)
                    .anyMatch(r -> !MsgSceneChannel.CH_INAPP.equals(r.getChannel()));
        }
    }

    @Test
    @DisplayName("★ 反向：配置表里不许出现消费者不认识的场景码 —— 那一行永远不会被用到")
    void everySeededSceneIsHandled() {
        List<String> seeded = mapper.selectList(Wrappers.<MsgSceneChannel>lambdaQuery()).stream()
                .map(MsgSceneChannel::getSceneCode)
                .distinct()
                .sorted()
                .toList();

        /*
         * 先钉住**读到了东西**，再做差集。
         *
         * 没有这一条的话：种子迁移改名、测试 profile 换了库、或者这张表哪天挂上数据域过滤
         * （本仓库带域表要 executeWithoutScope 才读得到），查询返回空集 ——
         * 差集自然为空，断言通过，而这条守卫**一行都没查过**。
         * 它守的正是「配置表里有一行谁都不读」，自己却可能什么都没读。
         */
        assertThat(seeded)
                .as("场景×通道配置表一行都没读到 —— 种子没跑？还是这张表被数据域过滤挡住了？"
                        + "读不到就等于没查，不能算通过")
                .hasSizeGreaterThanOrEqualTo(NotifyScene.ALL.size());

        List<String> unknown = seeded.stream()
                .filter(code -> !NotifyScene.ALL.contains(code))
                .toList();

        /*
         * 上面那条守的是「处理中的场景都落了种子」，这条守的是反过来那一向。
         * 两向的失败长得完全不同：
         *   · 漏种子 → 通知静默不外发（上面那条）
         *   · 种子里多一个码 → 那一行谁都不读，而运营在触达配置页上看得见它、
         *     还会去开关它，以为自己配的东西生效了
         * 场景×通道的行只来自迁移（`/ops/scene-channel` 只有 GET），所以这是一条真不变式。
         */
        assertThat(unknown)
                .as("这些场景码在配置表里有行，但 NotifyScene 里没有 —— 要么补常量与 case，要么那几行是废的：\n  %s",
                        String.join("\n  ", unknown))
                .isEmpty();
    }
}
