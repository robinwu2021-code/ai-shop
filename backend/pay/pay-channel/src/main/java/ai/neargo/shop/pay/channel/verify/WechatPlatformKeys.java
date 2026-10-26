package ai.neargo.shop.pay.channel.verify;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 验微信回调用的平台公钥，<b>按证书序列号（{@code Wechatpay-Serial}）选</b>，新旧两把并存（安全规范 G4 · §2.4；W2 S6）。
 *
 * <p>规则：
 * <ol>
 *   <li>回调带的 serial 认得 → 用那一把；</li>
 *   <li>认不得、但配了一把<b>没写 serial</b> 的 → 用它（存量配置只有一把公钥、从没配过 serial，升级不能把线上回调全拒了；
 *       轮换期它也是「旧的那把」的兜底）；</li>
 *   <li>否则拒：两把都有名字时，认不出的 serial 不猜 —— 猜错的结果要么是放进伪造，要么是把真回调拒了还看不出为什么。</li>
 * </ol>
 * 轮换步骤：配上新一把（{@code -next} 带 serial）→ 微信开始用新证书签 → 旧一把下线。顺序反了当天的回调全挂。
 */
public final class WechatPlatformKeys {

    private static final Logger log = LoggerFactory.getLogger(WechatPlatformKeys.class);

    private final Map<String, PublicKey> bySerial;
    private final PublicKey unnamed;

    private WechatPlatformKeys(Map<String, PublicKey> bySerial, PublicKey unnamed) {
        this.bySerial = Collections.unmodifiableMap(bySerial);
        this.unnamed = unnamed;
    }

    /**
     * @param primaryPem    当前公钥（PEM 或裸 Base64；空 = 没配）
     * @param primarySerial 它的序列号（空 = 不按 serial 认，见类注释第 2 条）
     * @param nextPem       轮换期的新一把（空 = 没在轮换）
     * @param nextSerial    新一把的序列号；配了新一把就必须配，否则两把都没名字分不开
     * @throws IllegalStateException 新一把没写 serial；或两把 serial 相同
     */
    public static WechatPlatformKeys of(String primaryPem, String primarySerial, String nextPem, String nextSerial) {
        Map<String, PublicKey> named = new LinkedHashMap<>();
        PublicKey unnamed = null;
        PublicKey primary = parse(primaryPem);
        if (primary != null) {
            if (blank(primarySerial)) {
                unnamed = primary;
            } else {
                named.put(primarySerial.trim(), primary);
            }
        }
        PublicKey next = parse(nextPem);
        if (next != null) {
            if (blank(nextSerial)) {
                throw new IllegalStateException("配了轮换期的新平台公钥（shop.pay.wechat.platform-public-key-next）却没配它的序列号"
                        + "（-next-serial）—— 两把都没名字，回调来了分不清该用哪一把");
            }
            if (named.put(nextSerial.trim(), next) != null) {
                throw new IllegalStateException("新旧两把平台公钥配了同一个序列号：" + nextSerial);
            }
        }
        return new WechatPlatformKeys(named, unnamed);
    }

    /** 按回调头里的 serial 选公钥；选不出为空（调用方据此拒） */
    public Optional<PublicKey> select(String serial) {
        if (!blank(serial)) {
            PublicKey k = bySerial.get(serial.trim());
            if (k != null) {
                return Optional.of(k);
            }
        }
        return Optional.ofNullable(unnamed);
    }

    /** 一把都没配（或都读不出来）：每条回调都会被拒 */
    public boolean isEmpty() {
        return bySerial.isEmpty() && unnamed == null;
    }

    private static PublicKey parse(String pem) {
        if (blank(pem)) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("\\s|-----[A-Z ]+-----", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            // 验签只做验证不签发：解析不了按「没配」处理（每条回调 fail-closed），不让整个应用因一份公钥起不来
            log.warn("[callback] 微信支付平台公钥解析失败，按未配置处理：{}", e.toString());
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
