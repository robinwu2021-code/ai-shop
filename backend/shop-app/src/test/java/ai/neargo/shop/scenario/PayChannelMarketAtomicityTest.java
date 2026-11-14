package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.pay.channel.entity.SysPayChannelMarket;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import ai.neargo.shop.pay.mapper.ChannelMappers.PayChannelMarketMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * F14 · Review R22：改通道市场要么整体生效、要么一点不动（TDD-支付服务-I1存量缺陷）。
 *
 * <p>市场替换是「先删后插」。中途失败（库连接断、某个市场码超长…）时，删已经发生、插只做了一半 ——
 * 而<b>空的市场集合在这套系统里就是「不限市场」</b>：一个只在大陆开的通道，就这样对全部市场开放了，没有任何报错。
 */
// 支付服务 I1–I3 的场景共用一个 H2 库（DB_CLOSE_DELAY=-1 的内存库活到 JVM 结束，每个库都是一整套表）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;"
        + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayChannelMarketAtomicityTest {

    @Autowired
    private PayChannelMasterService channels;

    @MockitoSpyBean
    private PayChannelMarketMapper markets;

    @AfterEach
    void restore() {
        org.mockito.Mockito.reset(markets);
        channels.updateSettings("WECHAT", true, "[\"CN\"]", "CNY", "T1");
    }

    @Test
    @DisplayName("★★★ F14 替换市场中途失败 → 原来的市场原样还在；不能留成空集（空集 = 全市场开放）")
    void failedReplaceLeavesOldMarketsIntact() {
        channels.updateSettings("WECHAT", true, "[\"CN\"]", "CNY", "T1");
        doThrow(new IllegalStateException("模拟：插到第二个市场时库连接断了"))
                .when(markets).insert(argThat((SysPayChannelMarket m) -> m != null && "TW".equals(m.getMarket())));

        assertThatThrownBy(() -> channels.updateSettings("WECHAT", true, "[\"HK\",\"TW\"]", "CNY", "T1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(channels.marketsOf("WECHAT"))
                .as("删了一半、插了一半：要么还是 [CN]，要么是 [HK,TW]，绝不能是 [HK] 或空")
                .containsExactly("CN");
    }
}
