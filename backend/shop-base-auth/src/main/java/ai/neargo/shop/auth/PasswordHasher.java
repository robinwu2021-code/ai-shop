package ai.neargo.shop.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希。**新值一律 bcrypt，旧值仍可验证**（存量账号靠登录自然升级）。
 *
 * <h2>它替换掉的东西有多弱</h2>
 * 一期占位实现是 {@code Integer.toHexString(("shop$" + raw).hashCode())}：
 * <ul>
 *   <li><b>32 位输出</b>，8 个 hex 字符 —— 生日碰撞在 2^16 量级，几万次尝试即可撞上</li>
 *   <li><b>无盐</b> —— 同密码同哈希，一张彩虹表通吃全部账号</li>
 *   <li><b>零计算成本</b> —— {@code String.hashCode} 是线性乘加，离线爆破每秒十亿计</li>
 * </ul>
 * 它不是「弱一点的哈希」，是**基本等价于明文**。
 *
 * <h2>格式自描述，不加字段</h2>
 * bcrypt 串以 {@code $2a$}/{@code $2b$} 开头，旧哈希是 8 位 hex ——
 * <b>看串本身就知道是哪种</b>，不需要在表上加 {@code password_algo} 列。
 * 少一个字段就少一处「忘了一起改」。
 *
 * <h2>升级只发生在验证通过之后</h2>
 * 那是唯一能拿到明文的时刻。验证失败也重写的话，等于把错误密码写进库 ——
 * 而那种故障发生在「用户下次输对了密码」的时刻，最难让人相信是系统的问题。
 */
@Component
public class PasswordHasher {

    /** bcrypt 串的前缀。{@code $2a$} / {@code $2b$} / {@code $2y$} 都以它开头 */
    private static final String BCRYPT_PREFIX = "$2";

    /**
     * 强度用默认值（cost 10）。
     *
     * <p><b>刻意不做成可配</b>：可配的安全参数迟早会被谁为了「本地跑快点」调低，
     * 然后带上生产。而这里没有需要调的理由 —— cost 10 在登录路径上约 100ms，
     * 那正是它该有的代价。
     */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 新密码一律 bcrypt。 */
    public String encode(String raw) {
        return encoder.encode(raw == null ? "" : raw);
    }

    /**
     * 验证。旧格式仍可通过 —— 存量账号不该被锁在门外。
     *
     * <p>空 stored 一律 false：数据异常时不能因为「两边都空」而放行。
     */
    public boolean matches(String raw, String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        String r = raw == null ? "" : raw;
        return stored.startsWith(BCRYPT_PREFIX)
                ? encoder.matches(r, stored)
                : legacyHash(r).equals(stored);
    }

    /**
     * 这个串是否还停在旧格式。
     *
     * <p>调用方在**验证通过之后**据此就地升级：
     * {@snippet :
     * if (hasher.matches(raw, staff.getPassword())) {
     *     if (hasher.needsUpgrade(staff.getPassword())) {
     *         staff.setPassword(hasher.encode(raw));
     *         staffMapper.updateById(staff);
     *     }
     *     // …发会话
     * }
     * }
     */
    public boolean needsUpgrade(String stored) {
        return stored != null && !stored.isBlank() && !stored.startsWith(BCRYPT_PREFIX);
    }

    /**
     * 一期占位哈希。**只保留给验证存量用，不再产出新值**。
     *
     * <p>包级可见而不是 public：它唯一的合法用途是本类内部的存量验证。
     * 留成 public 的话，某个新功能会「照着旁边的写法」再用它存一次密码。
     */
    static String legacyHash(String raw) {
        return Integer.toHexString(("shop$" + raw).hashCode());
    }
}
