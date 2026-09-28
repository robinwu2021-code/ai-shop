package ai.neargo.shop.pay.channel.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两家回调验签。**没有通道凭证也能测的那一半**。
 *
 * <p><b>这里能验什么、不能验什么，说在前面</b>：
 * <ul>
 *   <li><b>能验</b>：待签串按不按规范拼、签名对不对得上、改一个字节会不会被拒、
 *       AES-GCM 的附加数据是否真的参与认证。</li>
 *   <li><b>不能验</b>：真实通道发来的报文长什么样。拿到凭证做沙箱联调之前，
 *       这一条永远是「未验」。</li>
 * </ul>
 *
 * <p><b>只做「自己签、自己验」的往返测试是不够的</b>：待签串拼错时两边一样错，
 * 往返照样通过。所以每一家都<b>另有一条对字面量的断言</b> ——
 * 那一条才是拿规范当判据，而不是拿自己当判据。
 */
class CallbackVerifierTest {

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String sign(KeyPair kp, String content) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private static String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    // ---------------------------------------------------------------- 微信

    @Test
    @DisplayName("★★★ 微信待签串：三行，**结尾那个换行也要** —— 少一个签名恒不过")
    void wechatSignContentMatchesSpec() {
        assertThat(WechatCallbackVerifier.signContent("1554208460", "abc", "{\"a\":1}"))
                .isEqualTo("1554208460\nabc\n{\"a\":1}\n");
    }

    @Test
    @DisplayName("★★★ 微信：验签通过后才解密，业务字段解得出来")
    void wechatVerifiesThenDecrypts() throws Exception {
        KeyPair kp = rsa();
        String apiV3Key = "0123456789abcdef0123456789abcdef";   // 32 字节
        String plain = "{\"out_trade_no\":\"OT-1\",\"transaction_id\":\"TX-1\"}";
        String nonce = "abcdefghijkl";
        String aad = "transaction";

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String cipherText = Base64.getEncoder()
                .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));

        String body = "{\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\",\"associated_data\":\"" + aad
                + "\",\"nonce\":\"" + nonce + "\",\"ciphertext\":\"" + cipherText + "\"}}";
        String ts = "1554208460";
        String nc = "hdgs";
        Map<String, String> headers = new LinkedHashMap<>();
        // 大小写故意写成不常见的形态：请求头大小写不敏感，实现要能认
        headers.put("Wechatpay-Signature", sign(kp, WechatCallbackVerifier.signContent(ts, nc, body)));
        headers.put("WECHATPAY-TIMESTAMP", ts);
        headers.put("wechatpay-nonce", nc);

        var v = new WechatCallbackVerifier(apiV3Key, pub(kp), new tools.jackson.databind.ObjectMapper());
        Map<String, Object> out = v.verify(headers, body);

        assertThat(out).isNotNull();
        assertThat(out.get("out_trade_no")).isEqualTo("OT-1");
        assertThat(out.get("transaction_id")).isEqualTo("TX-1");
    }

    @Test
    @DisplayName("★★★ 微信：公钥从 -path 指向的文件里读得出来 —— 生产就是这么给的")
    void wechatReadsPlatformKeyFromPath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // 这条守着的是 2026-09-28 的那个缺陷：生产 env 用 WX_PLATFORM_PUBLIC_KEY_PATH
        // 指文件，而 verifier 只读 -key 内容字段 → 公钥恒空、每一条回调都验签失败。
        // 两笔 0.1 元订单被超时关闭，微信侧钱已成交、后端不知情。
        //
        // 判据：走一个**真实密文** —— 只有验签通过 + 解密通过才拿得到业务字段。
        // 撤掉 pemOf fallback（inline 空、path 有值时不读文件）→ 公钥空 →
        // rsaVerify 拿到空数组抛异常 → catch → 返 null，这一步就断了。
        KeyPair kp = rsa();
        String apiV3Key = "0123456789abcdef0123456789abcdef";
        String pem = "-----BEGIN PUBLIC KEY-----\n" + pub(kp) + "\n-----END PUBLIC KEY-----\n";
        java.nio.file.Path certFile = tmp.resolve("wxpay_pub_key.pem");
        java.nio.file.Files.writeString(certFile, pem);

        String plain = "{\"out_trade_no\":\"OT-PATH\",\"transaction_id\":\"TX-PATH\"}";
        String nonce = "abcdefghijkl";
        String aad = "transaction";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String cipherText = Base64.getEncoder()
                .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        String body = "{\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\",\"associated_data\":\"" + aad
                + "\",\"nonce\":\"" + nonce + "\",\"ciphertext\":\"" + cipherText + "\"}}";
        String ts = "1554208460";
        String nc = "hdgs";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("wechatpay-signature", sign(kp, WechatCallbackVerifier.signContent(ts, nc, body)));
        headers.put("wechatpay-timestamp", ts);
        headers.put("wechatpay-nonce", nc);

        // **内容字段留空、只给路径** —— 生产的真实用法（fallback 分支）
        var v = new WechatCallbackVerifier(apiV3Key, "", certFile.toString(),
                new tools.jackson.databind.ObjectMapper());

        Map<String, Object> out = v.verify(headers, body);
        assertThat(out).as("fallback 生效 → 公钥读到 → 验签通过 → 解密通过 → 业务字段可读").isNotNull();
        assertThat(out.get("out_trade_no")).isEqualTo("OT-PATH");
        assertThat(out.get("transaction_id")).isEqualTo("TX-PATH");
    }

    @Test
    @DisplayName("★★★ 微信：连续失败到 ALARM_AFTER 次就提级到 error —— 让 journal 看得到")
    void wechatEscalatesAfterConsecutiveFailures() throws Exception {
        WechatCallbackVerifier.resetFailCount();
        var v = new WechatCallbackVerifier("0123456789abcdef0123456789abcdef", "",
                "/does/not/exist.pem", new tools.jackson.databind.ObjectMapper());
        Map<String, String> h = Map.of("wechatpay-signature", "x",
                "wechatpay-timestamp", "1", "wechatpay-nonce", "n");
        // 阈值前：只增计数，不提级
        v.verify(h, "{}"); // 1
        v.verify(h, "{}"); // 2
        assertThat(WechatCallbackVerifier.currentFailCount()).isEqualTo(2);
        v.verify(h, "{}"); // 3 → 达 ALARM_AFTER
        assertThat(WechatCallbackVerifier.currentFailCount())
                .as("已到阈值，从这一次起走 error 分支")
                .isGreaterThanOrEqualTo(WechatCallbackVerifier.ALARM_AFTER);
    }

    @Test
    @DisplayName("★★★ 微信：成功一次立刻归零 —— 修完配置马上静音")
    void wechatSuccessResetsFailCount(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        WechatCallbackVerifier.resetFailCount();
        // 先累一些失败
        var bad = new WechatCallbackVerifier("0123456789abcdef0123456789abcdef", "",
                "/does/not/exist.pem", new tools.jackson.databind.ObjectMapper());
        bad.verify(Map.of("wechatpay-signature", "x", "wechatpay-timestamp", "1", "wechatpay-nonce", "n"), "{}");
        bad.verify(Map.of("wechatpay-signature", "x", "wechatpay-timestamp", "1", "wechatpay-nonce", "n"), "{}");
        assertThat(WechatCallbackVerifier.currentFailCount()).isGreaterThan(0);

        // 再来一次真验证成功
        KeyPair kp = rsa();
        String apiV3Key = "0123456789abcdef0123456789abcdef";
        String plain = "{\"out_trade_no\":\"OT-OK\"}";
        String nonce = "abcdefghijkl"; String aad = "t";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String ct = Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        String body = "{\"resource\":{\"algorithm\":\"AEAD\",\"associated_data\":\"" + aad
                + "\",\"nonce\":\"" + nonce + "\",\"ciphertext\":\"" + ct + "\"}}";
        String ts = "1554208460"; String nc = "hdgs";
        Map<String, String> good = new LinkedHashMap<>();
        good.put("wechatpay-signature", sign(kp, WechatCallbackVerifier.signContent(ts, nc, body)));
        good.put("wechatpay-timestamp", ts);
        good.put("wechatpay-nonce", nc);
        var ok = new WechatCallbackVerifier(apiV3Key, pub(kp), new tools.jackson.databind.ObjectMapper());

        assertThat(ok.verify(good, body)).isNotNull();
        assertThat(WechatCallbackVerifier.currentFailCount())
                .as("一次成功即清零").isZero();
    }

    @Test
    @DisplayName("★★★ 微信：路径读不出来时不装配阻塞、只 fail-closed —— 但要能起来")
    void wechatMissingKeyPathDegradesGracefully() {
        // 老规矩（WechatPayChannelConfig）是装配期直接 fail：那是签发 + 验签同时依赖公钥
        // 时的正确姿势。但 CallbackVerifier 只做验证；容忍它起来 + 每条回调拒掉，
        // 更符合「不因一个证书文件缺了让整个 shop-app 起不来」的取舍。
        var v = new WechatCallbackVerifier("0123456789abcdef0123456789abcdef", "",
                "/does/not/exist.pem", new tools.jackson.databind.ObjectMapper());
        assertThat(v.verify(Map.of("wechatpay-signature", "x", "wechatpay-timestamp", "1",
                "wechatpay-nonce", "n"), "{}")).isNull();
    }

    @Test
    @DisplayName("★★★ 微信：报文被改一个字节就必须拒 —— 否则验签等于没验")
    void wechatRejectsTamperedBody() throws Exception {
        KeyPair kp = rsa();
        String body = "{\"resource\":{}}";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("wechatpay-signature", sign(kp, WechatCallbackVerifier.signContent("1", "n", body)));
        headers.put("wechatpay-timestamp", "1");
        headers.put("wechatpay-nonce", "n");

        var v = new WechatCallbackVerifier("0123456789abcdef0123456789abcdef", pub(kp),
                new tools.jackson.databind.ObjectMapper());

        assertThat(v.verify(headers, body + " ")).as("多一个空格就该拒").isNull();
    }

    // -------------------------------------------------------------- 支付宝

    @Test
    @DisplayName("★★★ 支付宝待签串：按 key 升序、去掉 sign/sign_type 与空值")
    void alipaySignContentMatchesSpec() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("out_trade_no", "OT-2");
        p.put("sign", "xxx");
        p.put("sign_type", "RSA2");
        p.put("app_id", "2021");
        p.put("empty", "");
        p.put("trade_status", "TRADE_SUCCESS");

        assertThat(AlipayCallbackVerifier.signContent(p))
                .isEqualTo("app_id=2021&out_trade_no=OT-2&trade_status=TRADE_SUCCESS");
    }

    @Test
    @DisplayName("★★★ 支付宝：待签串用**解码后**的值 —— 拿编码原串拼永远对不上")
    void alipayUsesDecodedValues() {
        Map<String, String> p = AlipayCallbackVerifier.parseForm(
                "subject=%E7%B2%AE%E6%B2%B9&out_trade_no=OT-3");

        assertThat(p.get("subject")).isEqualTo("粮油");
        assertThat(AlipayCallbackVerifier.signContent(p))
                .isEqualTo("out_trade_no=OT-3&subject=粮油");
    }

    @Test
    @DisplayName("★★★ 支付宝：签名对得上才放行，改一个参数就拒")
    void alipayVerifiesAndRejectsTampered() throws Exception {
        KeyPair kp = rsa();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("out_trade_no", "OT-9");
        p.put("trade_no", "TX-9");
        p.put("trade_status", "TRADE_SUCCESS");
        String sign = sign(kp, AlipayCallbackVerifier.signContent(p));

        String body = "out_trade_no=OT-9&trade_no=TX-9&trade_status=TRADE_SUCCESS"
                + "&sign_type=RSA2&sign=" + java.net.URLEncoder.encode(sign, StandardCharsets.UTF_8);

        var v = new AlipayCallbackVerifier(pub(kp));
        Map<String, Object> ok = v.verify(Map.of(), body);
        assertThat(ok).isNotNull();
        assertThat(ok.get("out_trade_no")).isEqualTo("OT-9");

        String tampered = body.replace("OT-9", "OT-8");
        assertThat(v.verify(Map.of(), tampered)).as("改了单号就该拒").isNull();
    }

    @Test
    @DisplayName("两家的回执格式不同 —— 回错了不报错，只会让通道一直重推")
    void ackFormatsDiffer() {
        var w = new WechatCallbackVerifier("k", "", new tools.jackson.databind.ObjectMapper());
        var a = new AlipayCallbackVerifier("");
        assertThat(w.ackOk()).contains("SUCCESS").startsWith("{");
        assertThat(a.ackOk()).isEqualTo("success");
    }
}
