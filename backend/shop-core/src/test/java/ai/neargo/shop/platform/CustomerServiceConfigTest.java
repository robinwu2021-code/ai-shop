package ai.neargo.shop.platform;

import ai.neargo.shop.platform.config.BootstrapConfigService.CustomerService;
import ai.neargo.shop.platform.config.ShopProperties;
import ai.neargo.shop.platform.config.impl.BootstrapConfigServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信客服的接入参数要随冷启动一起发下去（TDD-微信客服接入 §4.1）。
 *
 * <p><b>为什么非得是冷启动这一跳</b>：{@code wx.openCustomerServiceChat} 在 iOS 上
 * 必须由用户手势<u>直接</u>触发，先 {@code await} 再调会被判「并非点击触发」而失败
 * （Android 却能过 —— 所以这是一个只在 iOS 真机上现形的坑）。
 * 于是这两个值必须在点击<b>之前</b>就躺在端上，不能点的时候现拉。
 *
 * <p>这里断的是<b>「空」怎么发</b>。端上按「任一为空就回落到 open-type=contact」判，
 * 而回落这件事只有在后端<b>发空串而不是 null、也不是半截</b>时才成立。
 */
class CustomerServiceConfigTest {

    private static CustomerService kfOf(String corpId, String url) {
        var props = new ShopProperties();
        props.getCustomerService().setCorpId(corpId);
        props.getCustomerService().setUrl(url);
        return new BootstrapConfigServiceImpl(props).get().customerService();
    }

    @Test
    @DisplayName("★★★ 配齐了就原样发下去 —— 端上拿不到这两个值就点不开客服")
    void bothConfiguredAreSentThrough() {
        var kf = kfOf("ww1234567890abcdef", "https://work.weixin.qq.com/kfid/kfc0123456789");

        assertThat(kf.corpId()).isEqualTo("ww1234567890abcdef");
        assertThat(kf.url()).isEqualTo("https://work.weixin.qq.com/kfid/kfc0123456789");
    }

    @Test
    @DisplayName("★★ 没配时发空串而不是 null —— 端上的「空 = 回落」只有这样才成立")
    void unconfiguredIsEmptyStringNotNull() {
        var kf = kfOf(null, null);

        assertThat(kf.corpId()).isNotNull().isEmpty();
        assertThat(kf.url()).isNotNull().isEmpty();
    }

    /**
     * <b>只配一半是最危险的一种</b>：两个值分别来自企业微信后台的两个地方
     * （CorpID 在「我的企业」，接入链接在「微信客服 → 客服账号详情」），
     * 配的人很容易只填到手的那一个。
     *
     * <p>那时端上必须整体回落 —— 拿着半截参数去调，失败是静默的，
     * 界面上与「压根没配」长得一模一样。所以这里断的是**另一个也得是空**，
     * 而不只是「那个没填的是空」。
     */
    @Test
    @DisplayName("★★★ 只配一半：两个都照原样发，端上据此整体回落（判据在端上，这里保证不发半截谎）")
    void halfConfiguredStillReportsTheTruth() {
        var onlyCorp = kfOf("ww1234567890abcdef", "  ");
        assertThat(onlyCorp.corpId()).isEqualTo("ww1234567890abcdef");
        assertThat(onlyCorp.url()).as("空白要 trim 成空串，否则端上的 `url ? a : b` 会把一串空格当成配好了").isEmpty();

        var onlyUrl = kfOf("", "https://work.weixin.qq.com/kfid/kfc0123456789");
        assertThat(onlyUrl.corpId()).isEmpty();
        assertThat(onlyUrl.url()).isNotEmpty();
    }
}
