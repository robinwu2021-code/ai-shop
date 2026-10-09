package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 来单推商家自己的企微群（TDD-商家企微群来单通知 §5）。
 *
 * <p>不起 Spring、不发 HTTP：{@link WeComBotSender} 用一个记下「发到哪个 URL、发了什么」
 * 的替身。这里要断言的两件事都与 HTTP 无关 —— <b>发到哪个群</b>，以及<b>内容长什么样</b>。
 */
class WeComOrderAlertTest {

    private static final String ENTITY = "MCH-1";
    private static final String GROUP_A = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=aaa";

    /** 记下每一次发送的目标与内容 */
    private record Sent(String bizType, String content, String webhook) {
    }

    private final List<Sent> sent = new ArrayList<>();

    private WeComBotSender recordingSender() {
        WeComBotSender s = mock(WeComBotSender.class);
        when(s.sendMarkdown(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sent.add(new Sent(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
            return true;
        });
        return s;
    }

    /** 商家 ENTITY 的群是 GROUP_A；其余商家没配 */
    private MerchantWecomWebhook webhooksWith(String entityNo, String url) {
        MerchantChannelService channels = mock(MerchantChannelService.class);
        NotifyChannel ch = new NotifyChannel();
        ch.setChannelType(NotifyChannel.TYPE_WEBHOOK);
        ch.setProvider(NotifyChannel.PROV_WECOM);
        ch.setScope(NotifyChannel.SCOPE_MERCHANT);
        ch.setOwnerNo(entityNo);
        ch.setEnabled(true);
        ch.setSecretCipher("whatever-cipher");
        when(channels.listForOwner(anyString())).thenReturn(List.of());
        when(channels.listForOwner(entityNo)).thenReturn(List.of(ch));
        when(channels.decryptSecret(any())).thenReturn("{\"webhook\":\"" + url + "\"}");
        return new MerchantWecomWebhook(channels, new ObjectMapper());
    }

    private MerchantQueryPort merchantWith(Map<String, String> storeNames, String merchantName) {
        MerchantQueryPort p = mock(MerchantQueryPort.class);
        when(p.storeNames(any())).thenReturn(storeNames);
        when(p.find(anyString())).thenReturn(merchantName == null ? Optional.empty()
                : Optional.of(new MerchantQueryPort.MerchantBrief(ENTITY, merchantName,
                        true, true, null, 5, 0, true, 0, true)));
        return p;
    }

    private SubOrderBuyerPort subOrderWith(String firstName, int count) {
        SubOrderBuyerPort p = mock(SubOrderBuyerPort.class);
        when(p.itemsOf(anyString())).thenReturn(firstName == null ? Optional.empty()
                : Optional.of(new SubOrderBuyerPort.ItemsBrief(firstName, count)));
        return p;
    }

    private WeComOrderAlert alert(MerchantWecomWebhook hooks, MerchantQueryPort mch,
                                 SubOrderBuyerPort sub) {
        return new WeComOrderAlert(hooks, recordingSender(), mch, sub);
    }

    @Test
    @DisplayName("★★★ 发到**那个商家名下**的群，不是平台那条（AC2）")
    void sendsToMerchantOwnGroup() {
        var a = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of("ST-1", "虹选演示店"), "虹选"), subOrderWith("土豆", 3));

        assertThat(a.paid(ENTITY, "ST-1", "SUB-1", 1234)).isTrue();
        assertThat(sent).singleElement().satisfies(s -> assertThat(s.webhook()).isEqualTo(GROUP_A));
    }

    @Test
    @DisplayName("★★★ 商家没配群 → **一条都不发**，绝不回落到平台那条 env（AC2）")
    void unconfiguredMerchantSendsNothing() {
        // 配的是 MCH-1 的群，来的单属于 MCH-2
        var a = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of(), "别家"), subOrderWith("土豆", 1));

        assertThat(a.paid("MCH-2", "ST-9", "SUB-2", 500)).isFalse();
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("停用的那行不算配了（AC2）")
    void disabledChannelCountsAsUnconfigured() {
        MerchantChannelService channels = mock(MerchantChannelService.class);
        NotifyChannel ch = new NotifyChannel();
        ch.setChannelType(NotifyChannel.TYPE_WEBHOOK);
        ch.setProvider(NotifyChannel.PROV_WECOM);
        ch.setOwnerNo(ENTITY);
        ch.setEnabled(false);
        ch.setSecretCipher("c");
        when(channels.listForOwner(ENTITY)).thenReturn(List.of(ch));

        assertThat(new MerchantWecomWebhook(channels, new ObjectMapper()).of(ENTITY)).isEmpty();
    }

    @Test
    @DisplayName("门店、金额、商品、单号四行都在；金额是元不是分（AC4）")
    void contentCarriesStoreAmountItemsAndNo() {
        var a = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of("ST-1", "虹选演示店"), "虹选"), subOrderWith("土豆", 3));

        String md = a.content(ENTITY, "ST-1", "SUB202610092051300002260", 1234);

        assertThat(md).contains("**新订单**")
                .contains("> 门店：虹选演示店")
                .contains("> 金额：￥12.34")
                .contains("> 商品：土豆 等 3 件")
                .contains("> 单号：SUB202610092051300002260")
                .doesNotContain("1234分");
    }

    @Test
    @DisplayName("查不到门店名 → 回落主体名；两个都查不到 → 省掉那一行（AC4）")
    void missingStoreNameFallsBack() {
        String fellBack = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of(), "虹选超市"), subOrderWith("土豆", 1))
                .content(ENTITY, "ST-1", "SUB-1", 100);
        assertThat(fellBack).contains("> 门店：虹选超市");

        String omitted = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of(), null), subOrderWith("土豆", 1))
                .content(ENTITY, "ST-1", "SUB-1", 100);
        assertThat(omitted).doesNotContain("门店").contains("> 金额：").contains("> 单号：");
    }

    @Test
    @DisplayName("只有一件 → 不写「等 1 件」（AC4）")
    void singleItemOmitsCount() {
        String md = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of(), "虹选"), subOrderWith("土豆", 1))
                .content(ENTITY, null, "SUB-1", 100);
        assertThat(md).contains("> 商品：土豆").doesNotContain("等 1 件");
    }

    @Test
    @DisplayName("一件明细都查不到 → 省掉商品那一行，不显示「共 0 件」（AC4）")
    void noItemsOmitsLine() {
        String md = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of(), "虹选"), subOrderWith(null, 0))
                .content(ENTITY, null, "SUB-1", 100);
        assertThat(md).doesNotContain("商品").doesNotContain("0 件");
    }

    @Test
    @DisplayName("★★★ 内容里**没有买家信息** —— 群里人多，手机号/地址不进群（AC4）")
    void noBuyerInfoInContent() {
        String md = alert(webhooksWith(ENTITY, GROUP_A),
                merchantWith(Map.of("ST-1", "虹选演示店"), "虹选"), subOrderWith("土豆", 3))
                .content(ENTITY, "ST-1", "SUB-1", 1234);
        // 排版只有这四个键，多一个都要在这里显式加 —— 买家信息要进群得先过这条断言
        assertThat(md.lines().filter(l -> l.startsWith("> ")).map(l -> l.substring(2, l.indexOf('：')))
                .toList()).containsExactly("门店", "金额", "商品", "单号");
    }
}
