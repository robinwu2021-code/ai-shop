package ai.neargo.shop.message.notify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.message.entity.MchNotifyRecipient;
import ai.neargo.shop.message.mapper.MessageMappers.MchNotifyRecipientMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门店的通知收件地址（TDD-来单四渠道与商家通知设置 §2.5）。
 *
 * <p>这里守的是三条规矩：短信**最多两个额外号**、号与邮箱的形状、
 * 以及「存的是逗号分隔、给出去的是列表」这层转换只在一处做。
 */
@DisplayName("门店通知收件地址")
class MerchantNotifyRecipientsTest {

    private static final String STORE = "ST-1";

    private MerchantNotifyRecipients service(MchNotifyRecipient existing) {
        MchNotifyRecipientMapper mapper = mock(MchNotifyRecipientMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        return new MerchantNotifyRecipients(mapper);
    }

    private static MchNotifyRecipient row(String phones, String email, String webhook) {
        MchNotifyRecipient r = new MchNotifyRecipient();
        r.setStoreNo(STORE);
        r.setSmsPhones(phones);
        r.setEmail(email);
        r.setWecomWebhook(webhook);
        return r;
    }

    @Test
    @DisplayName("★★★ 额外短信号**最多两个** —— 第三个整笔拒（用户 2026-10-10）")
    void atMostTwoExtraPhones() {
        var s = service(null);
        assertThatThrownBy(() -> s.setPhones(STORE,
                List.of("13700000001", "13700000002", "13700000003"), "op"))
                .isInstanceOf(BizException.class);
        assertThat(MchNotifyRecipient.MAX_EXTRA_PHONES).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 不是大陆手机号的整笔拒 —— 短信走的是国内模板，别的号发不出去")
    void rejectsNonMainlandPhone() {
        var s = service(null);
        assertThatThrownBy(() -> s.setPhones(STORE, List.of("12345"), "op"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> s.setPhones(STORE, List.of("+8613700000001"), "op"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 明显填错的邮箱拒掉 —— 填错的症状是「开着却收不到」，那时没有线索")
    void rejectsMalformedEmail() {
        var s = service(null);
        assertThatThrownBy(() -> s.setEmail(STORE, "not-an-email", "op"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> s.setEmail(STORE, "a@b", "op"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 企微地址前缀不对就拒 —— 填错的话企微那边连请求都收不到，日志里什么都没有")
    void rejectsWrongWebhookPrefix() {
        var s = service(null);
        assertThatThrownBy(() -> s.setWecom(STORE, "https://example.com/hook", "op"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ 逗号分隔只在一处展开 —— 顺带去空白、丢空串")
    void splitHappensInOnePlace() {
        assertThat(MerchantNotifyRecipients.splitPhones("13700000001, 13700000002"))
                .containsExactly("13700000001", "13700000002");
        assertThat(MerchantNotifyRecipients.splitPhones("13700000001,,")).containsExactly("13700000001");
        assertThat(MerchantNotifyRecipients.splitPhones("")).isEmpty();
        assertThat(MerchantNotifyRecipients.splitPhones(null)).isEmpty();
    }

    @Test
    @DisplayName("读出来的是列表而不是那串逗号 —— 调用方不该知道存储形态")
    void readsAsList() {
        var s = service(row("13700000001,13700000002", "a@b.com", "https://qyapi.weixin.qq.com/x"));
        assertThat(s.extraPhones(STORE)).containsExactly("13700000001", "13700000002");
        assertThat(s.email(STORE)).contains("a@b.com");
        assertThat(s.wecomWebhook(STORE)).contains("https://qyapi.weixin.qq.com/x");
    }

    @Test
    @DisplayName("没配过的门店三样都是空 —— 不抛，不是错误状态")
    void unconfiguredStoreIsEmpty() {
        var s = service(null);
        assertThat(s.extraPhones(STORE)).isEmpty();
        assertThat(s.email(STORE)).isEmpty();
        assertThat(s.wecomWebhook(STORE)).isEmpty();
    }

    @Test
    @DisplayName("空串当成「没填」而不是「填了个空的」")
    void blankCountsAsUnset() {
        var s = service(row("  ", "  ", "  "));
        assertThat(s.extraPhones(STORE)).isEmpty();
        assertThat(s.email(STORE)).isEmpty();
        assertThat(s.wecomWebhook(STORE)).isEmpty();
    }
}
