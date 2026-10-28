package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.terminal.TerminalMappers.PayManagerCodeMapper;
import ai.neargo.shop.pay.terminal.entity.PayManagerCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/**
 * 店长授权码（pay-guard 开发计划 M6.4；代码清单 §4.2）：终端撤销 / 退款要店长当场输入的码。商家端设置，库里只存
 * 加盐慢哈希（PBKDF2-HMAC-SHA256）；一家店连续输错 {@value #MAX_FAILURES} 次锁 {@code 15} 分钟 —— 锁的是这家店的全部授权码，
 * 不让人在终端上逐个店长去试。授权码是业务输入、不是调用方身份，所以在业务层验，不在守卫里。
 */
@Service
public class ManagerCodeService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayManagerCodeMapper codes;

    public ManagerCodeService(PayManagerCodeMapper codes) {
        this.codes = codes;
    }

    /** 设置 / 重置：6–12 位数字。重置即覆盖旧码，并解除本店的锁定 */
    public void set(Long storeId, Long managerAccountId, String code) {
        if (storeId == null || managerAccountId == null || code == null || !code.matches("\\d{6,12}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PayManagerCode existing = codes.selectOne(Wrappers.<PayManagerCode>lambdaQuery()
                .eq(PayManagerCode::getStoreId, storeId).eq(PayManagerCode::getManagerAccountId, managerAccountId));
        PayManagerCode row = existing == null ? new PayManagerCode() : existing;
        row.setStoreId(storeId);
        row.setManagerAccountId(managerAccountId);
        row.setCodeHash(hash(code));
        row.setStatus("ACTIVE");
        row.setFailedAttempts(0);
        row.setLockedUntil(null);
        if (existing == null) {
            codes.insert(row);
        } else {
            codes.updateById(row);
        }
        // 锁是整店的（见类注释），由店长在商家端重设授权码来解：清掉本店所有码的失败计数与锁定
        codes.update(null, Wrappers.<PayManagerCode>lambdaUpdate().eq(PayManagerCode::getStoreId, storeId)
                .set(PayManagerCode::getFailedAttempts, 0).set(PayManagerCode::getLockedUntil, null));
    }

    /** 验码：对上返回店长账号 id；对不上 / 锁定中 → {@link ErrorCode#FORBIDDEN}（不说是哪种） */
    public Long verify(Long storeId, String code) {
        List<PayManagerCode> active = codes.selectList(Wrappers.<PayManagerCode>lambdaQuery()
                .eq(PayManagerCode::getStoreId, storeId).eq(PayManagerCode::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (active.isEmpty() || active.stream().anyMatch(c -> c.getLockedUntil() != null && c.getLockedUntil().isAfter(now))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (code != null) {
            for (PayManagerCode c : active) {
                if (matches(code, c.getCodeHash())) {
                    active.forEach(x -> codes.update(null, Wrappers.<PayManagerCode>lambdaUpdate()
                            .eq(PayManagerCode::getId, x.getId()).set(PayManagerCode::getFailedAttempts, 0)));
                    return c.getManagerAccountId();
                }
            }
        }
        int failures = active.stream().mapToInt(c -> c.getFailedAttempts() == null ? 0 : c.getFailedAttempts()).max().orElse(0) + 1;
        LocalDateTime lock = failures >= MAX_FAILURES ? now.plus(LOCK) : null;
        active.forEach(x -> codes.update(null, Wrappers.<PayManagerCode>lambdaUpdate().eq(PayManagerCode::getId, x.getId())
                .set(PayManagerCode::getFailedAttempts, lock == null ? failures : 0)
                .set(PayManagerCode::getLockedUntil, lock)));
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    static String hash(String code) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(code, salt, ITERATIONS));
    }

    static boolean matches(String code, String stored) {
        String[] p = stored == null ? new String[0] : stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(p[3]);
        return MessageDigest.isEqual(expected, derive(code, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] derive(String code, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
