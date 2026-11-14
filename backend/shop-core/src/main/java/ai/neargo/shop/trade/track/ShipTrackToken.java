package ai.neargo.shop.trade.track;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 看件页的免登录令牌（TDD-收件人物流触达与分享裂变 §4）。
 *
 * <p><b>为什么是无状态 HMAC，不建表</b>：这张票据发给的是**收件人**——一个
 * 没有、也不该有账号的人。建一张 token 表意味着每次发货都写一行、还要清过期行；
 * 而 token 要表达的只有两件事：「这是哪张子单」「有没有过期」。
 * 两件都能塞进票据本身，服务端拿密钥一验即可 —— 库里什么都不留。
 *
 * <p><b>为什么要 exp</b>：短信里的链接会被转发、截图、留在聊天记录里。
 * 没有过期时间的话，一张三个月前的发货短信今天点开还能看到收货人地址和电话。
 * 给它一个有限寿命（默认 {@value #DEFAULT_TTL_DAYS} 天），过期后看件页只说「链接已失效」。
 *
 * <p>票据格式 {@code base64url(payload) "." base64url(hmac)}，
 * payload = {@code subOrderNo "|" expEpochSec}。验签用**定长时间比较**，
 * 防止靠响应时间逐字节猜签名。
 *
 * <p><b>密钥的失败方式：fail closed</b>。没配 {@code SHIP_TRACK_KEY} 时，只有开发/测试 profile
 * （{@link #DEV_PROFILES}）才退到固定开发默认值；其余环境**不签发、也不认任何票**。
 *
 * <p>此前是一律退到开发默认值并告警 —— 可那把默认密钥就写在源码里，等于公开：
 * 生产漏配一次，任何人都能给任意子单号签出看件链接，看到收件人姓名、电话和地址。
 * 「页面打不开」是看得见的故障，「链接可被伪造」是看不见的泄露，宁可前者。
 * 不在启动时直接失败：api/ops/worker 各面都装这个 bean，而只有发货短信与看件页用它，
 * 漏配不该连带把下单、运营后台一起拖挂。
 */
@Component
public class ShipTrackToken {

    private static final Logger log = LoggerFactory.getLogger(ShipTrackToken.class);

    private static final String HMAC = "HmacSHA256";
    private static final long DEFAULT_TTL_DAYS = 30;
    /** 只在没配 key、且处于 {@link #DEV_PROFILES} 时使用。源码里的串等于公开，绝不能进生产 */
    private static final String DEV_KEY = "ai-shop-dev-ship-track-key-do-not-use-in-prod";

    /** 允许退到开发默认密钥的 profile。test / e2e 组都带 testcfg（见 application.yml 的 profiles.group） */
    static final String[] DEV_PROFILES = {"dev", "local", "test", "testcfg"};

    /** 没配密钥且不在开发 profile 时为 null：此时 {@link #sign} 拒绝、{@link #verify} 恒空 */
    private final byte[] key;
    private final Duration ttl;

    @Autowired
    public ShipTrackToken(@Value("${shop.ship.track-key:}") String keyConf,
                          @Value("${shop.ship.track-ttl-days:30}") long ttlDays,
                          Environment env) {
        this(keyConf, ttlDays, env.matchesProfiles(DEV_PROFILES));
    }

    /** 直接构造（单测用）：不在开发 profile，没给密钥即 fail closed */
    public ShipTrackToken(String keyConf, long ttlDays) {
        this(keyConf, ttlDays, false);
    }

    private ShipTrackToken(String keyConf, long ttlDays, boolean allowDevKey) {
        if (keyConf != null && !keyConf.isBlank()) {
            this.key = keyConf.trim().getBytes(StandardCharsets.UTF_8);
        } else if (allowDevKey) {
            log.warn("[ship-track] 未配置 shop.ship.track-key（SHIP_TRACK_KEY）——"
                    + " 开发/测试 profile 下用开发默认密钥，生产必须配");
            this.key = DEV_KEY.getBytes(StandardCharsets.UTF_8);
        } else {
            log.error("[ship-track] 未配置 shop.ship.track-key（SHIP_TRACK_KEY）——"
                    + " 看件令牌停用：不签发、不验票，发货短信与看件页将不可用，直到配上密钥");
            this.key = null;
        }
        this.ttl = Duration.ofDays(ttlDays > 0 ? ttlDays : DEFAULT_TTL_DAYS);
    }

    /** 签发一张看指定子单的票据，有效期从现在起 {@link #ttl} */
    public String sign(String subOrderNo) {
        return signWithExp(subOrderNo, System.currentTimeMillis() / 1000 + ttl.toSeconds());
    }

    /**
     * 指定过期秒签发。**仅供测试**造「已过期」的票 ——
     * 公开的 {@link #sign} 永远签未来，没法用它验「过期即失效」那条。
     */
    String signWithExp(String subOrderNo, long expEpochSec) {
        if (key == null) {
            // 调用方（发货短信）会吞掉并留痕；签一张谁都能照着伪造的票，比不发更糟
            throw new IllegalStateException("看件令牌密钥未配置（SHIP_TRACK_KEY），拒绝签发");
        }
        byte[] p = (subOrderNo + "|" + expEpochSec).getBytes(StandardCharsets.UTF_8);
        return b64(p) + "." + b64(hmac(p));
    }

    /**
     * 验票。返回子单号；票据被篡改、格式不对、或已过期时为空。
     *
     * <p>调用方据空与非空决定给看件页还是给「链接已失效」——不区分「伪造」与「过期」，
     * 对着一个匿名来访者没必要把失败原因说清楚。
     */
    public Optional<String> verify(String token) {
        if (key == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        byte[] p, sig;
        try {
            p = Base64.getUrlDecoder().decode(token.substring(0, dot));
            sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(sig, hmac(p))) {
            return Optional.empty();
        }
        String payload = new String(p, StandardCharsets.UTF_8);
        int bar = payload.lastIndexOf('|');
        if (bar <= 0) {
            return Optional.empty();
        }
        long exp;
        try {
            exp = Long.parseLong(payload.substring(bar + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() / 1000 > exp) {
            return Optional.empty();
        }
        return Optional.of(payload.substring(0, bar));
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            // key 长度任意合法、算法 JDK 自带 —— 走到这里是环境坏了，不该静默
            throw new IllegalStateException("看件令牌签名失败", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
