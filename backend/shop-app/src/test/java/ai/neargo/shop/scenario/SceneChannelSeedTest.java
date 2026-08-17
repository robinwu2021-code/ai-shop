package ai.neargo.shop.scenario;

import ai.neargo.shop.message.NotificationConsumer;
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
}
