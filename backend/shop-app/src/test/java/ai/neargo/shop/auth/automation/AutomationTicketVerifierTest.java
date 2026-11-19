package ai.neargo.shop.auth.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 密钥票据校验（ADR-027）。每一条拒绝路径都各有一条用例 —— 任何一条放过，都是一扇没人察觉的门。
 */
@DisplayName("密钥票据：只认本机私钥签的、60 秒内、一次性、白名单里的")
class AutomationTicketVerifierTest {

    private static final long NOW = 1_790_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    static final KeyPair KEYS = gen();
    static final KeyPair OTHER = gen();

    static KeyPair gen() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pub(KeyPair k) {
        return Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
    }

    static String ticket(PrivateKey key, String json) {
        try {
            String p = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(("v1." + p).getBytes(StandardCharsets.US_ASCII));
            return "v1." + p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String payload(String realm, String sub, long exp, String nonce) {
        return "{\"realm\":\"" + realm + "\",\"sub\":\"" + sub + "\",\"exp\":" + exp + ",\"nonce\":\"" + nonce + "\"}";
    }

    private final AutomationTicketVerifier v =
            new AutomationTicketVerifier(true, pub(KEYS), "B:U1, OPS:STF1", CLOCK);

    private String ok(String realm, String sub, String nonce) {
        return ticket(KEYS.getPrivate(), payload(realm, sub, NOW + 60, nonce));
    }

    private void rejected(String ticket, String code) {
        assertThatThrownBy(() -> v.verify(ticket))
                .isInstanceOf(AutomationTicketVerifier.Rejected.class)
                .extracting(e -> ((AutomationTicketVerifier.Rejected) e).code()).isEqualTo(code);
    }

    @Test
    @DisplayName("★★★ 本机私钥签的、白名单里的：店主与运营都放行")
    void validTicketsPass() {
        assertThat(v.verify(ok("B", "U1", "nonce-aaaaaaaaaaaa1")))
                .isEqualTo(new AutomationTicketVerifier.Verified(AutomationTicketVerifier.TicketRealm.B, "U1"));
        assertThat(v.verify(ok("OPS", "STF1", "nonce-aaaaaaaaaaaa2")).realm())
                .isEqualTo(AutomationTicketVerifier.TicketRealm.OPS);
    }

    @Test
    @DisplayName("★★★ 别的私钥签的 → 拒（线上拿不到私钥，就签不出票据）")
    void otherKeyRejected() {
        rejected(ticket(OTHER.getPrivate(), payload("B", "U1", NOW + 60, "nonce-bbbbbbbbbbbb1")),
                "AUTOMATION_BAD_SIGNATURE");
    }

    @Test
    @DisplayName("★★★ 改了 payload（比如把账号换成别人）→ 签名对不上")
    void tamperedPayloadRejected() {
        String t = ok("B", "U1", "nonce-cccccccccccc1");
        String[] parts = t.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload("B", "U2", NOW + 60, "nonce-cccccccccccc1").getBytes(StandardCharsets.UTF_8));
        rejected("v1." + forged + "." + parts[2], "AUTOMATION_BAD_SIGNATURE");
    }

    @Test
    @DisplayName("★★ 过期的、有效期签得太远的 → 拒")
    void expiryEnforced() {
        rejected(ticket(KEYS.getPrivate(), payload("B", "U1", NOW, "nonce-dddddddddddd1")), "AUTOMATION_EXPIRED");
        rejected(ticket(KEYS.getPrivate(), payload("B", "U1", NOW + 3600, "nonce-dddddddddddd2")),
                "AUTOMATION_TTL_TOO_LONG");
    }

    @Test
    @DisplayName("★★★ 同一张票据用第二次 → 拒（截获的票据没有复用价值）")
    void replayRejected() {
        String t = ok("B", "U1", "nonce-eeeeeeeeeeee1");
        v.verify(t);
        rejected(t, "AUTOMATION_REPLAYED");
    }

    @Test
    @DisplayName("★★★ 签名对、但账号不在白名单 → 拒（私钥泄露时的损失上限就是白名单）")
    void notInAllowlistRejected() {
        rejected(ok("B", "U999", "nonce-ffffffffffff1"), "AUTOMATION_NOT_ALLOWED");
        rejected(ok("OPS", "U1", "nonce-ffffffffffff2"), "AUTOMATION_NOT_ALLOWED");
    }

    @Test
    @DisplayName("★ 格式不对、realm 不认识、nonce 太短 → 拒")
    void malformedRejected() {
        rejected("garbage", "AUTOMATION_MALFORMED");
        rejected("v2.a.b", "AUTOMATION_MALFORMED");
        rejected(ticket(KEYS.getPrivate(), payload("X", "U1", NOW + 60, "nonce-gggggggggggg1")),
                "AUTOMATION_BAD_REALM");
        // C 是认识的端（消费者），但白名单按「端:账号」记：只列了 B:U1，C:U1 签不出来
        rejected(ok("C", "U1", "nonce-gggggggggggg2"), "AUTOMATION_NOT_ALLOWED");
        rejected(ticket(KEYS.getPrivate(), payload("B", "U1", NOW + 60, "short")), "AUTOMATION_MALFORMED");
    }

    @Test
    @DisplayName("★★ 开关关着：什么票据都拒；开着却没配公钥或白名单：拒绝启动")
    void switchAndStartupValidation() {
        AutomationTicketVerifier off = new AutomationTicketVerifier(false, "", "", CLOCK);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.verify(ok("B", "U1", "nonce-hhhhhhhhhhhh1")))
                .isInstanceOf(AutomationTicketVerifier.Rejected.class);

        assertThatThrownBy(() -> new AutomationTicketVerifier(true, "", "B:U1", CLOCK))
                .hasMessageContaining("PUBLIC_KEY");
        assertThatThrownBy(() -> new AutomationTicketVerifier(true, pub(KEYS), " ", CLOCK))
                .hasMessageContaining("SUBJECTS");
        assertThatThrownBy(() -> new AutomationTicketVerifier(true, "bm90LWEta2V5", "B:U1", CLOCK))
                .hasMessageContaining("Ed25519");
    }

    @Test
    @DisplayName("★★★ 钥按端分：运营端的钥签不出店主的票据；没单独配的端用通用的那一把；一端可以认多把")
    void keysArePerRealm() throws Exception {
        KeyPair opsKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair extraB = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var perRealm = java.util.Map.of(
                AutomationTicketVerifier.TicketRealm.OPS, pub(opsKey),
                AutomationTicketVerifier.TicketRealm.B, pub(KEYS) + "," + pub(extraB));
        var v = new AutomationTicketVerifier(true, pub(KEYS), perRealm, "B:U1, OPS:STF1, C:U1", CLOCK);

        // 各端用自己的钥：通过
        assertThat(v.verify(ticket(opsKey.getPrivate(), payload("OPS", "STF1", NOW + 30, "nonce-perrealm-00001"))).subject())
                .isEqualTo("STF1");
        assertThat(v.verify(ticket(extraB.getPrivate(), payload("B", "U1", NOW + 30, "nonce-perrealm-00002"))).subject())
                .isEqualTo("U1");
        // C 没有单独配：用通用的那一把
        assertThat(v.verify(ticket(KEYS.getPrivate(), payload("C", "U1", NOW + 30, "nonce-perrealm-00003"))).subject())
                .isEqualTo("U1");

        // 拿别的端的钥签：签名是真的，但不认
        assertThatThrownBy(() -> v.verify(ticket(opsKey.getPrivate(), payload("B", "U1", NOW + 30, "nonce-perrealm-00004"))))
                .isInstanceOf(AutomationTicketVerifier.Rejected.class).hasMessage("AUTOMATION_WRONG_KEY_FOR_REALM");
        assertThatThrownBy(() -> v.verify(ticket(KEYS.getPrivate(), payload("OPS", "STF1", NOW + 30, "nonce-perrealm-00005"))))
                .isInstanceOf(AutomationTicketVerifier.Rejected.class).hasMessage("AUTOMATION_WRONG_KEY_FOR_REALM");
        // 被拒的票据不占 nonce：同一个 nonce 换对的钥还能用
        assertThat(v.verify(ticket(KEYS.getPrivate(), payload("B", "U1", NOW + 30, "nonce-perrealm-00004"))).subject())
                .isEqualTo("U1");

        // 白名单里有运营端，而运营端一把钥都没有：拒绝启动
        assertThatThrownBy(() -> new AutomationTicketVerifier(true, "",
                java.util.Map.of(AutomationTicketVerifier.TicketRealm.B, pub(KEYS)), "B:U1, OPS:STF1", CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPS");
    }
}
