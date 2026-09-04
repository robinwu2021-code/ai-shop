package ai.neargo.shop.pay.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信 APIv3 签名。
 *
 * <h2>为什么这组用例要逐字节比，而不是「能签出来就行」</h2>
 * 签名是这条链上<b>唯一一处「写错了也一切正常，直到真的发出去」</b>的代码。
 * 少一个换行、方法名小写、query 没进待签串 —— 本地怎么跑都不报错，
 * 到通道那里统一表现为「签名错误」，而那个错误看起来像凭据配错，
 * 会让人去查证书、去重下公钥、去重设 APIv3 密钥，
 * <b>而问题在一个字符上</b>。
 *
 * <p>所以每一条都带<b>反向控制量</b>：把实现里那一处去掉，断言必须变红。
 * 只断言「签出来非空」的测试对这类缺陷是全盲的。
 */
class WechatApiV3SignerTest {

    private static final String MCH_ID = "1900000109";
    private static final String SERIAL_NO = "5157F09EFDC096DE15EBE81A47057A72";

    // ---------------------------------------------------------------- 待签串（纯函数）

    @Test
    @DisplayName("请求待签串：五行，结尾那个换行也要")
    void 请求待签串逐字节() {
        String c = WechatApiV3Signer.signContent(
                "POST", "/v3/pay/transactions/jsapi", 1700000000L, "NONCE1", "{\"a\":1}");

        assertThat(c).isEqualTo("POST\n/v3/pay/transactions/jsapi\n1700000000\nNONCE1\n{\"a\":1}\n");

        // 反向控制量：去掉结尾换行必须不等 —— 少这一个 \n 签名恒不过
        assertThat(c).isNotEqualTo("POST\n/v3/pay/transactions/jsapi\n1700000000\nNONCE1\n{\"a\":1}");
    }

    @Test
    @DisplayName("GET 的 body 那一行是空串加换行，不是没有这一行")
    void GET待签串保留空body行() {
        String c = WechatApiV3Signer.signContent(
                "GET", "/v3/pay/transactions/out-trade-no/P1?mchid=" + MCH_ID,
                1700000000L, "NONCE1", "");

        assertThat(c).isEqualTo(
                "GET\n/v3/pay/transactions/out-trade-no/P1?mchid=1900000109\n1700000000\nNONCE1\n\n");
        // 五个换行，一个都不能少
        assertThat(c.chars().filter(ch -> ch == '\n').count()).isEqualTo(5);
    }

    @Test
    @DisplayName("query 必须参与签名 —— 带不带 ?mchid= 是两个不同的待签串")
    void query参与签名() {
        String withQuery = WechatApiV3Signer.signContent(
                "GET", "/v3/pay/transactions/out-trade-no/P1?mchid=" + MCH_ID, 1L, "N", "");
        String without = WechatApiV3Signer.signContent(
                "GET", "/v3/pay/transactions/out-trade-no/P1", 1L, "N", "");

        assertThat(withQuery).isNotEqualTo(without);
    }

    @Test
    @DisplayName("小程序 paySign 待签串：四行，第四行是带 prepay_id= 前缀的整串")
    void paySign待签串逐字节() {
        String c = WechatApiV3Signer.paySignContent(
                "wxAPPID", "1700000000", "NONCE1", "prepay_id=wx0123");

        assertThat(c).isEqualTo("wxAPPID\n1700000000\nNONCE1\nprepay_id=wx0123\n");

        // 反向控制量：传裸 prepay_id 的话端上报「支付验证签名失败」，而后端一切正常
        assertThat(c).isNotEqualTo(
                WechatApiV3Signer.paySignContent("wxAPPID", "1700000000", "NONCE1", "wx0123"));
    }

    // ---------------------------------------------------------------- 真签真验

    @Test
    @DisplayName("签出来的名字能被对应公钥验回；改一个字节必须验不过")
    void 签名可被验回且改一字节必红() throws Exception {
        KeyPair kp = rsa();
        WechatApiV3Signer signer = signerOf(kp);

        String body = "{\"out_trade_no\":\"P1\"}";
        String sig = signer.sign(WechatApiV3Signer.responseSignContent("1700000000", "N1", body));

        assertThat(signer.verifyResponse("1700000000", "N1", body, sig)).isTrue();

        // ---- 反向控制量三条，任何一条为 true 都说明验签是摆设 ----
        assertThat(signer.verifyResponse("1700000000", "N1", body + " ", sig))
                .as("body 多一个空格").isFalse();
        assertThat(signer.verifyResponse("1700000001", "N1", body, sig))
                .as("时间戳改一位").isFalse();
        assertThat(signer.verifyResponse("1700000000", "N2", body, sig))
                .as("随机串换一个").isFalse();
    }

    @Test
    @DisplayName("Authorization 头：认证类型、五个字段齐，且签的正是那五行")
    void authorization头() throws Exception {
        KeyPair kp = rsa();
        WechatApiV3Signer signer = signerOf(kp);

        String auth = signer.authorization("POST", "/v3/pay/transactions/jsapi",
                "{\"a\":1}", "NONCE1", 1700000000L);

        assertThat(auth).startsWith("WECHATPAY2-SHA256-RSA2048 ");
        assertThat(auth).contains("mchid=\"" + MCH_ID + "\"");
        assertThat(auth).contains("serial_no=\"" + SERIAL_NO + "\"");
        assertThat(auth).contains("nonce_str=\"NONCE1\"");
        assertThat(auth).contains("timestamp=\"1700000000\"");

        /*
         * **签的内容对不对，才是这条用例的重点。**
         * 头里有五个字段但签的是别的串 —— 那种缺陷上面四条断言全绿。
         * 这里把签名取出来，用同一把公钥去验「请求待签串」。
         */
        String signature = between(auth, "signature=\"", "\"");
        java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
        v.initVerify(kp.getPublic());
        v.update(WechatApiV3Signer.signContent("POST", "/v3/pay/transactions/jsapi",
                1700000000L, "NONCE1", "{\"a\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(v.verify(Base64.getDecoder().decode(signature))).isTrue();
    }

    @Test
    @DisplayName("没配公钥时 canVerifyResponse=false，且验签一律不通过（不是「默认通过」）")
    void 没配公钥不假装验过() throws Exception {
        KeyPair kp = rsa();
        WechatApiV3Signer signer = new WechatApiV3Signer(MCH_ID, SERIAL_NO, pkcs8(kp), null);

        assertThat(signer.canVerifyResponse()).isFalse();
        // 默认通过的话，一个完全没有校验的通道看起来会是安全的
        assertThat(signer.verifyResponse("1", "N", "{}", "AAAA")).isFalse();
    }

    @Test
    @DisplayName("随机串 32 位且每次都换 —— 复用等于给重放留门")
    void 随机串不重复() {
        String a = WechatApiV3Signer.nonce();
        String b = WechatApiV3Signer.nonce();
        assertThat(a).hasSize(32);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("私钥缺失在构造时就炸，不是等到第一次下单")
    void 缺私钥装配期就炸() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new WechatApiV3Signer(MCH_ID, SERIAL_NO, "  ", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key");
    }

    // ---------------------------------------------------------------- 夹具

    static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static String pkcs8(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
    }

    static String x509(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    static WechatApiV3Signer signerOf(KeyPair kp) {
        return new WechatApiV3Signer(MCH_ID, SERIAL_NO, pkcs8(kp), x509(kp));
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from) + from.length();
        return s.substring(a, s.indexOf(to, a));
    }
}
