package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.master.PayChannelRateService;
import ai.neargo.shop.pay.channel.entity.SysPayChannelRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通道费率：按生效时间分版本、精确优先于通配、没配就是没配。
 *
 * <p>费率是**会被翻旧账**的东西：真正会被问到的是「上个月那批单当时按什么费率算的」。
 * 所以只增不改，取的时候按时刻回放。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayChannelRateFlowTest {

    @Autowired
    private PayChannelRateService rates;

    private SysPayChannelRate add(String channel, String pm, String lf, int bp, long from) {
        SysPayChannelRate r = new SysPayChannelRate();
        r.setPayChannel(channel);
        r.setPayMethod(pm);
        r.setLegalForm(lf);
        r.setRateBp(bp);
        r.setEffectiveFrom(from);
        return rates.add(r);
    }

    @Test
    @DisplayName("★★★ 没配费率返回 null —— 不许兜 0，兜 0 等于悄悄按「零手续费」算账")
    void noRateReturnsNullNotZero() {
        assertThat(rates.effective("NOPE-CH", "*", "*", System.currentTimeMillis()))
                .as("一条都没配就是没配").isNull();
    }

    @Test
    @DisplayName("★★★ 按时刻回放：调费率之前的单仍取旧版本")
    void ratesAreReplayedByTime() {
        String ch = "RATE-T1";
        add(ch, "*", "*", 38, 1_000L);
        add(ch, "*", "*", 60, 5_000L);

        assertThat(rates.effective(ch, "*", "*", 3_000L).rateBp())
                .as("3000 这一刻只有第一版生效").isEqualTo(38);
        assertThat(rates.effective(ch, "*", "*", 9_000L).rateBp())
                .as("调完之后取新版").isEqualTo(60);
        assertThat(rates.effective(ch, "*", "*", 500L))
                .as("第一版生效之前，没有任何一版").isNull();
    }

    @Test
    @DisplayName("★★★ 精确优先于通配 —— 反过来的话「企业专属费率」永远取不到")
    void exactBeatsWildcard() {
        String ch = "RATE-T2";
        add(ch, "*", "*", 38, 1_000L);
        add(ch, "JSAPI", "ENTERPRISE", 20, 1_000L);

        assertThat(rates.effective(ch, "JSAPI", "ENTERPRISE", 2_000L).rateBp())
                .as("两维都精确命中").isEqualTo(20);
        assertThat(rates.effective(ch, "JSAPI", "MICRO", 2_000L).rateBp())
                .as("主体形态没有专属版，回落通配").isEqualTo(38);
    }

    @Test
    @DisplayName("★★★ 未来时刻的版本 = 预约生效，此刻取不到")
    void futureVersionIsScheduledNotActive() {
        String ch = "RATE-T3";
        long now = System.currentTimeMillis();
        add(ch, "*", "*", 38, now - 1000);
        add(ch, "*", "*", 25, now + 86_400_000L);

        assertThat(rates.effective(ch, "*", "*", now).rateBp())
                .as("明天才生效的那一版，今天不能用").isEqualTo(38);
        assertThat(rates.history(ch)).as("但它要在列表里看得见").hasSize(2);
    }

    @Test
    @DisplayName("★★★ 万分比越界当场拒 —— 把 0.38% 写成 38% 是最容易犯的那种错")
    void rateOutOfRangeRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> add("RATE-T4", "*", "*", 20000, 1L))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}
