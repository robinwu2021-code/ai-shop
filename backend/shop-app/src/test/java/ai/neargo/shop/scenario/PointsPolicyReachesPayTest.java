package ai.neargo.shop.scenario;

import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.PointsService.ClientPointsPolicy;
import ai.neargo.shop.payclient.PointsPolicyAppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营端改了积分端策略，<b>支付域要读得到</b>。
 *
 * <h2>这条守的是一个真实发生过的缺陷</h2>
 * M2 把 {@code points.client.policy} 搬进支付域的设置表时漏了运营端那一侧：
 * 运营端写 {@code sys_setting}，而支付域读 {@code pay_setting}。
 * 后果是运营禁用某个端的积分发放，<b>保存成功、页面回显正确，而积分照发</b> ——
 * 一个「改了没生效且不报错」的开关。
 *
 * <p>它没有被更早发现，是因为线上两张表里这个键<b>都没有值</b>：
 * 运营从没改过它，两边一直在用同一个代码默认值，于是「读不到」这件事
 * 表现为「读到的正好也对」。<b>这类缺陷靠观察生产是发现不了的。</b>
 *
 * <p>所以这条断言必须<b>穿过两个服务</b>：从运营端的入口写进去，
 * 从支付域的出口读出来。只测其中一侧的话，两侧各自都是自洽的。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointsPolicyReachesPayTest {

    @Autowired
    private PointsPolicyAppService opsSide;

    @Autowired
    private PointsService paySide;

    /** 用完还原：这是全局设置，留着会让别的用例里「某个端不发积分」 */
    @AfterEach
    void restore() {
        opsSide.save(new ClientPointsPolicy(List.of(), List.of(), true), "TEST-RESTORE");
    }

    @Test
    @DisplayName("★★★ 运营端禁掉一个端的发放，支付域当场就该拦住 —— 中间隔着两张设置表")
    void opsChangeReachesPayDomain() {
        // 前置：默认什么都不禁，支付域应当放行
        assertThat(paySide.canEarn(null, "WECHAT", PayScenes.MP_WECHAT).allowed())
                .as("前置不成立：默认策略下就已经拦住了，那下面那条断言什么都证明不了")
                .isTrue();

        // 运营端禁掉微信小程序的积分发放
        opsSide.save(new ClientPointsPolicy(List.of(PayScenes.MP_WECHAT), List.of(), true), "OPS1");

        var after = paySide.canEarn(null, "WECHAT", PayScenes.MP_WECHAT);
        assertThat(after.allowed())
                .as("运营端保存成功，而支付域读不到 —— 两边写的不是同一张设置表。"
                        + "这正是 2026-09-01 之前的实况：开关改了，积分照发，且不报错")
                .isFalse();
        assertThat(after.reason()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 运营端读回的也要是支付域那份 —— 否则页面回显是对的而实际没生效")
    void opsReadBackComesFromSameTable() {
        opsSide.save(new ClientPointsPolicy(List.of(PayScenes.H5), List.of(), false), "OPS2");

        assertThat(opsSide.policy().earnDeny()).containsExactly(PayScenes.H5);
        assertThat(opsSide.policy().offlineRedeem()).isFalse();
        // 反向控制量：换一个端问，必须不受影响
        assertThat(paySide.canEarn(null, "WECHAT", PayScenes.MP_WECHAT).allowed()).isTrue();
    }
}
