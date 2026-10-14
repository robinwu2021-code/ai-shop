package ai.neargo.shop.unit;

import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上报一次之后该落成什么状态。<b>纯函数，不起 Spring</b>。
 *
 * <p>三个分支的代价完全不对称：成功记成待上报会一直重复报；
 * 可重试记成 FAILED 会让一笔本来能成的单永远不再试，
 * <b>而那意味着这笔钱结不出来</b>；不可重试记成待上报则会一直占着队列，
 * 真正该人工看的单没人看。
 */
class WxShippingOutcomeTest {

    @Test
    @DisplayName("★★★ 成功 → SUCCESS 且终态")
    void successIsTerminal() {
        var o = WxShippingUploadService.outcomeOf(0, WxShippingPort.Result.ok());
        assertThat(o.status()).isEqualTo(TrdShippingUpload.SUCCESS);
        assertThat(o.giveUp()).isTrue();
        assertThat(o.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 不可重试 → FAILED，立刻交给人")
    void fatalGivesUpAtOnce() {
        var o = WxShippingUploadService.outcomeOf(0,
                WxShippingPort.Result.fatal(10060011, "未开通"));
        assertThat(o.status()).isEqualTo(TrdShippingUpload.FAILED);
        assertThat(o.giveUp()).isTrue();
    }

    @Test
    @DisplayName("★★★ 可重试且没到上限 → 留在队列，次数要涨")
    void retryableStaysQueued() {
        var o = WxShippingUploadService.outcomeOf(2, WxShippingPort.Result.retry(-1, "读超时"));
        assertThat(o.status()).isEqualTo(TrdShippingUpload.PENDING);
        assertThat(o.giveUp()).isFalse();
        assertThat(o.attempts())
                .as("次数不涨的话上限就是摆设，这笔单会永远重试")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★★★ 可重试但到了上限 → 也转 FAILED —— 永远重试等于永远没人看")
    void retryableStopsAtTheCap() {
        var o = WxShippingUploadService.outcomeOf(7, WxShippingPort.Result.retry(-1, "读超时"));
        assertThat(o.attempts()).isEqualTo(8);
        assertThat(o.status()).isEqualTo(TrdShippingUpload.FAILED);
        assertThat(o.giveUp()).isTrue();
    }
}
