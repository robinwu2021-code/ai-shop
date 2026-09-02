package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.pay.service.recon.ReconAxis;
import ai.neargo.shop.pay.service.recon.SplitReconAxis;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账覆盖范围要说真话（S16）。
 *
 * <h2>先说量到的</h2>
 * 设计册的 S16 是「对账覆盖范围结构化 —— 今天只有一句话」，
 * O6 是「运营端要能看出哪些方向没有账可对」。
 * 查了一遍：<b>那一屏已经有了</b>（在 {@code /orders}，不在 {@code /finance}，
 * 我先找错了地方）。它把三件事分得很开 ——
 * 零差异 / 这条轴没跑成 / 这条轴查不到那一类，覆盖说明永远显示、不折叠。
 * 这是这一轮第六次「以为没有其实有」。
 *
 * <h2>真正的问题是那句话会过期</h2>
 * 覆盖说明是<b>写死的字符串</b>。分账那条里有一句
 * 「分账网关目前是桩实现，所以每一笔已发出的单都不会有回执」——
 * 接了真通道之后它就是假话，<b>而没有任何东西会提醒</b>：
 * 文案不会因为换了实现而报错，页面照常显示，读的人照常相信
 * 「这批未确认只是因为还没接通道」。
 *
 * <p>于是一批<b>真的没划走的钱</b>会被当成已知现象放过去 ——
 * 而这条轴存在的全部意义就是发现那件事。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconCoverageHonestyTest {

    @Autowired
    private ReconService reconService;
    @Autowired
    private List<ReconAxis> axes;

    private static final String STUB_SENTENCE = "分账网关目前是桩实现";

    @Test
    @DisplayName("★★★ 桩网关在场时说明里有那句话 —— 否则运营会把一批未确认当成通道故障")
    void stubCaveatPresentWithStubGateway() {
        var split = axes.stream().filter(a -> a instanceof SplitReconAxis).findFirst().orElseThrow();

        assertThat(split.coverage().note())
                .as("测试环境用的正是桩网关，这句话必须在")
                .contains(STUB_SENTENCE);
    }

    @Test
    @DisplayName("★★★ 换成会回执的实现，那句话必须自己消失 —— 写死的话它会变成假话且不报")
    void stubCaveatDisappearsWithRealGateway() {
        /*
         * 直接 new 一个轴：coverage() 只用到 staleHours 与网关，
         * 两个 mapper 传 null 不会被碰到。
         *
         * **这一条才是这组的重点** —— 上一条只证明「现在这句话在」，
         * 而写死的字符串也能让它通过。
         */
        SplitGateway realish = new SplitGateway() {
            @Override
            public Result split(String s, String p, long a, String r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result reverse(String s, String p, long a, String r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result subsidy(String s, String p, long a, String r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result subsidyReturn(String s, String p, long a, String r) {
                throw new UnsupportedOperationException();
            }
            // deliversConfirmation 用接口默认值 true —— 真实现不该为此写一行
        };
        var axis = new SplitReconAxis(null, null, realish, 24);

        assertThat(axis.coverage().note())
                .as("接了真通道之后这句话还在 —— 那批未确认会被当成「还没接通道」放过去，"
                        + "而它们其实是真的没划走的钱")
                .doesNotContain(STUB_SENTENCE);
        assertThat(axis.coverage().note())
                .as("整句都没了也不对：平台侧自查这件事与网关是不是桩无关")
                .contains("只有平台侧自查");
    }

    @Test
    @DisplayName("★★★ 每条轴都得说清覆盖范围 —— 空白说明在页面上等于「这条轴什么都看得见」")
    void everyAxisDeclaresCoverage() {
        assertThat(axes)
                .as("一条轴都没扫到 —— 下面的断言什么都没证明")
                .hasSizeGreaterThanOrEqualTo(4);

        for (var a : axes) {
            var c = a.coverage();
            assertThat(c).as(a.getClass().getSimpleName() + " 没有覆盖范围").isNotNull();
            assertThat(c.note())
                    .as(a.getClass().getSimpleName() + " 的覆盖说明是空的。"
                            + "页面上那张卡片下面会是一片空白 —— 读的人会以为这条轴什么都看得见，"
                            + "而四条轴今天都只有我方自查这一侧")
                    .isNotBlank();
            if (!c.complete()) {
                assertThat(c.note())
                        .as(a.getClass().getSimpleName() + " 说自己不完整，却没说少了什么")
                        .hasSizeGreaterThan(20);
            }
        }
    }

    @Test
    @DisplayName("★★ 总入口跑得动，且每条轴都带着覆盖范围回来")
    void scanAllAxesCarriesCoverage() {
        var reports = reconService.scanAllAxes(System.currentTimeMillis());

        assertThat(reports).hasSameSizeAs(axes);
        assertThat(reports).allSatisfy(r -> {
            assertThat(r.coverage()).as(r.axis() + " 的报告里没有覆盖范围").isNotNull();
            assertThat(r.coverage().note()).isNotBlank();
        });
    }
}
