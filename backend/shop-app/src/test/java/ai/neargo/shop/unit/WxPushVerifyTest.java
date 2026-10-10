package ai.neargo.shop.unit;

import ai.neargo.shop.portal.mp.MpWxCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * 微信消息推送的握手校验。
 *
 * <p><b>为什么值得测：这段代码只在「后台点提交」那一刻跑一次</b>，
 * 平时没有任何流量经过它。写错了不会有人发现，直到某天要配推送，
 * 而那时微信只回一句「token 验证失败」——它不会告诉你是签名算错了、
 * 还是返回值被信封裹住了、还是 nginx 根本没把这个路径转过来。
 *
 * <p>最容易漏的是**排序**：三个值要按字典序排再拼。漏掉排序时，
 * 只要自测用的 token/timestamp/nonce 恰好已经有序，一样能通过。
 */
class WxPushVerifyTest {

    private static final String TOKEN = "hxmallToken2026";

    /** 按微信规则独立算一遍，不复用被测代码 —— 复用等于用它自己证明自己 */
    private static String sign(String token, String ts, String nonce) throws Exception {
        String[] a = {token, ts, nonce};
        Arrays.sort(a);
        byte[] d = MessageDigest.getInstance("SHA-1")
                .digest(String.join("", a).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("★★★ 签名正确 → 原样回 echostr（不是包在信封里的 echostr）")
    void echoesBackOnValidSignature() throws Exception {
        MpWxCallbackController c = ctl(TOKEN);
        String ts = "1788400000", nonce = "zzz999";
        assertThat(c.verify(sign(TOKEN, ts, nonce), ts, nonce, "ECHO-1")).isEqualTo("ECHO-1");
    }

    @Test
    @DisplayName("★★★ 三个值要按字典序排 —— 漏了排序时，用有序的入参自测会碰巧通过")
    void sortsBeforeHashing() throws Exception {
        MpWxCallbackController c = ctl(TOKEN);
        /*
         * 刻意挑一组**未排序**的：token 以 h 开头，排序后要落到中间。
         * 不排序直接拼 token+ts+nonce 会得到完全不同的摘要。
         */
        String ts = "1788400000";      // '1' 最小
        String nonce = "zzz999";       // 'z' 最大
        assertThat(sign(TOKEN, ts, nonce))
                .as("这组入参本身必须是无序的，否则这条用例证明不了排序")
                .isNotEqualTo(hashRaw(TOKEN + ts + nonce));
        assertThat(c.verify(sign(TOKEN, ts, nonce), ts, nonce, "OK")).isEqualTo("OK");
    }

    @Test
    @DisplayName("★★ 签名不符 → 回空串，不回 echostr")
    void rejectsBadSignature() {
        MpWxCallbackController c = ctl(TOKEN);
        assertThat(c.verify("deadbeef", "1788400000", "zzz999", "ECHO")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 没配 token → 拒绝，不放行（放行等于谁都能冒充微信推事件）")
    void refusesWhenTokenMissing() throws Exception {
        for (String unset : new String[]{"", "   ", null}) {
            MpWxCallbackController c = ctl(unset);
            assertThat(c.verify(sign("", "1", "2"), "1", "2", "ECHO"))
                    .as("token=%s 时必须拒绝", String.valueOf(unset))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 事件推送回 success —— 不回微信会重推三次并在后台标失败")
    void acksEvents() throws Exception {
        String ts = "1700000000";
        String nonce = "n1";
        assertThat(ctl(TOKEN).receive(sign(TOKEN, ts, nonce), ts, nonce, "{\"MsgType\":\"event\"}"))
                .isEqualTo("success");
        assertThat(ctl(TOKEN).receive(sign(TOKEN, ts, nonce), ts, nonce, null)).isEqualTo("success");
    }

    @Test
    @DisplayName("★★★ POST 也要验签 —— 签名不符的事件必须被丢掉，不能进到改状态那一步")
    void postRejectsBadSignature() {
        var events = org.mockito.Mockito.mock(ai.neargo.shop.paybridge.WxPushEventService.class);
        MpWxCallbackController c = new MpWxCallbackController(TOKEN, events);

        // 回 success 是给微信看的（别重推），但事件不能被处理
        assertThat(c.receive("deadbeef", "1700000000", "n1", "{}")).isEqualTo("success");
        assertThat(c.receive(null, null, null, "{}")).isEqualTo("success");
        org.mockito.Mockito.verify(events, org.mockito.Mockito.never()).onEvent(any());
    }

    @Test
    @DisplayName("★★★ 签名正确才把事件交下去处理")
    void postPassesVerifiedEvent() throws Exception {
        var events = org.mockito.Mockito.mock(ai.neargo.shop.paybridge.WxPushEventService.class);
        MpWxCallbackController c = new MpWxCallbackController(TOKEN, events);
        String ts = "1700000000";
        String nonce = "n1";

        c.receive(sign(TOKEN, ts, nonce), ts, nonce, "{\"Event\":\"x\"}");

        org.mockito.Mockito.verify(events).onEvent("{\"Event\":\"x\"}");
    }

    @Test
    @DisplayName("★★ 事件处理抛异常不能冒出去 —— 冒出去微信会一直重推")
    void postSwallowsHandlerFailure() throws Exception {
        var events = org.mockito.Mockito.mock(ai.neargo.shop.paybridge.WxPushEventService.class);
        org.mockito.Mockito.when(events.onEvent(any())).thenThrow(new IllegalStateException("boom"));
        MpWxCallbackController c = new MpWxCallbackController(TOKEN, events);
        String ts = "1700000000";
        String nonce = "n1";

        assertThat(c.receive(sign(TOKEN, ts, nonce), ts, nonce, "{}")).isEqualTo("success");
    }

    private static MpWxCallbackController ctl(String token) {
        return new MpWxCallbackController(token,
                org.mockito.Mockito.mock(ai.neargo.shop.paybridge.WxPushEventService.class));
    }

    private static String hashRaw(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
