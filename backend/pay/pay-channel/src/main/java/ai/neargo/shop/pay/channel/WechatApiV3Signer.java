package ai.neargo.shop.pay.channel;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 APIv3 的签名、验签与小程序 paySign。
 *
 * <h2>为什么单独成一个类，而不是写在 HTTP 客户端里</h2>
 * 签名是这条链上<b>唯一一处「写错了也一切正常，直到真的发出去」</b>的代码：
 * 少一个换行、方法名大小写不对、query 没参与签名 —— 本地怎么跑都不报错，
 * 到通道那里统一表现为「签名错误」，而那个错误看起来像凭据配错，
 * 会让人去查一个没问题的地方。
 *
 * <p>拆出来是为了让它<b>不发 HTTP 也能逐字节测</b>：
 * 待签串的构造是纯函数（{@link #signContent} / {@link #paySignContent}），
 * 测试可以拿官方文档里的例子逐字节比对，并做反向控制量
 * （改一个字节必须不等）。混在客户端里的话，测它就得先造一个假的网络层，
 * 那时测的已经不是签名了。
 *
 * <p><b>本类持有私钥，且只持有</b>：不提供任何返回密钥的方法，
 * 不把密钥作为参数传出去，异常信息里也不带密钥片段。
 */
public class WechatApiV3Signer {

    private static final String SIGN_ALG = "SHA256withRSA";
    /** Authorization 的认证类型，微信固定这个串 */
    private static final String AUTH_TYPE = "WECHATPAY2-SHA256-RSA2048";
    /** 小程序 {@code requestPayment} 的签名类型。APIv3 下固定 RSA */
    public static final String SIGN_TYPE = "RSA";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** 微信要求随机串 ≤ 32 位 */
    private static final int NONCE_LEN = 32;

    private final String mchId;
    private final String serialNo;
    private final PrivateKey privateKey;
    private final PublicKey platformPublicKey;

    /**
     * @param mchId             商户号
     * @param serialNo          商户 API 证书序列号
     * @param privateKeyPem     {@code apiclient_key.pem} 的内容（PKCS#8）
     * @param platformPublicKeyPem 微信支付公钥（或平台证书公钥）。为空则**不验应答签名**，
     *                          见 {@link #canVerifyResponse()}
     */
    public WechatApiV3Signer(String mchId, String serialNo,
                             String privateKeyPem, String platformPublicKeyPem) {
        this.mchId = mchId;
        this.serialNo = serialNo;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.platformPublicKey = parsePublicKey(platformPublicKeyPem);
    }

    // ------------------------------------------------------------ 待签串（纯函数）

    /**
     * 请求的待签串。<b>五行，最后一行也要换行</b>。
     *
     * <pre>
     * HTTP方法\n
     * URL（含 query）\n
     * 时间戳\n
     * 随机串\n
     * 请求报文主体\n
     * </pre>
     *
     * <p>GET 没有 body，那一行是<b>空串加换行</b>，不是没有这一行 ——
     * 少一个 {@code \n} 签名恒不过，而失败信息里看不出少的是哪一个。
     */
    public static String signContent(String method, String urlWithQuery, long timestamp,
                                     String nonce, String body) {
        return method + "\n" + urlWithQuery + "\n" + timestamp + "\n" + nonce + "\n"
                + (body == null ? "" : body) + "\n";
    }

    /**
     * 小程序调起支付的待签串。<b>四行</b>，与请求签名不是同一套。
     *
     * <pre>
     * 小程序appId\n
     * 时间戳\n
     * 随机串\n
     * package（形如 prepay_id=xxx）\n
     * </pre>
     *
     * <p>第四行是 {@code prepay_id=} <b>带前缀的整串</b>，不是裸的 prepay_id。
     * 传裸值的话端上报「支付验证签名失败」，而后端一切正常。
     */
    public static String paySignContent(String appId, String timeStamp, String nonceStr,
                                        String prepayPackage) {
        return appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + prepayPackage + "\n";
    }

    /** 微信应答/回调的待签串：三行。与 {@code WechatCallbackVerifier} 用的是同一套。 */
    public static String responseSignContent(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
    }

    // ------------------------------------------------------------ 签名

    /** 随机串。每次调用都要换 —— 复用等于给重放留门。 */
    public static String nonce() {
        StringBuilder sb = new StringBuilder(NONCE_LEN);
        for (int i = 0; i < NONCE_LEN; i++) {
            sb.append(NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 用商户私钥对任意串做 SHA256withRSA，Base64 输出。 */
    public String sign(String content) {
        try {
            Signature s = Signature.getInstance(SIGN_ALG);
            s.initSign(privateKey);
            s.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            // 不带出 content —— 它里面有报文主体
            throw new ChannelClient.ChannelException("微信请求签名失败：" + e.getClass().getSimpleName(), false);
        }
    }

    /**
     * 完整的 {@code Authorization} 头。
     *
     * <p>字段顺序按微信文档写死。顺序本身不参与签名，但换个顺序就得让读代码的人
     * 再去核一遍文档 —— 与文档同序是最省事的注释。
     */
    public String authorization(String method, String urlWithQuery, String body,
                                String nonce, long timestamp) {
        String signature = sign(signContent(method, urlWithQuery, timestamp, nonce, body));
        return AUTH_TYPE + " "
                + "mchid=\"" + mchId + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + serialNo + "\"";
    }

    /** 小程序 {@code requestPayment} 的 paySign。 */
    public String jsapiPaySign(String appId, String timeStamp, String nonceStr, String prepayPackage) {
        return sign(paySignContent(appId, timeStamp, nonceStr, prepayPackage));
    }

    // ------------------------------------------------------------ 验应答

    /**
     * 配了公钥才验得了。<b>没配时调用方要按「验不了」处理并记一行</b>，
     * 不能当成「验过了」—— 后者会让一个没有任何校验的通道看起来是安全的。
     */
    public boolean canVerifyResponse() {
        return platformPublicKey != null;
    }

    /**
     * 验微信应答的签名。不验的话，能改我方出网流量的人可以把
     * 「下单失败」改成「下单成功」，而我方会照着假回执给用户一个付不了的收银台。
     */
    public boolean verifyResponse(String timestamp, String nonce, String body, String signatureBase64) {
        if (platformPublicKey == null || timestamp == null || nonce == null || signatureBase64 == null) {
            return false;
        }
        try {
            Signature v = Signature.getInstance(SIGN_ALG);
            v.initVerify(platformPublicKey);
            v.update(responseSignContent(timestamp, nonce, body).getBytes(StandardCharsets.UTF_8));
            return v.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ PEM

    /** PEM → DER。去掉 {@code -----BEGIN/END xxx-----} 与所有空白。 */
    static byte[] der(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("\\s|-----[A-Z ]+-----", ""));
    }

    private static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            // 私钥缺失是**装配期就该炸**的事：带着空私钥启动的话，
            // 第一次真实下单才失败，而那时用户已经在收银台前面了
            throw new IllegalStateException(
                    "微信支付私钥未配置（shop.pay.wechat.private-key）—— 拒绝以无私钥状态装配");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("微信支付私钥解析失败，需 PKCS#8 的 apiclient_key.pem", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("微信支付公钥解析失败（shop.pay.wechat.platform-public-key）", e);
        }
    }
}
