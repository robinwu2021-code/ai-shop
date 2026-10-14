package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.StaffLoginPhonePort;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.entity.UsrOtpTestPhone;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.OtpTestPhoneMapper;
import ai.neargo.shop.user.service.OtpTestPhoneService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试号白名单的四条护栏 + 整表缓存。
 *
 * <h2>为什么带缓存</h2>
 *
 * {@link #fixedCodeFor} 在<b>每一次「获取验证码」</b>上都会被调到，而这张表是
 * 「一两行、几个月改一次」的东西 —— 逐次查库等于给每次发码都加一趟往返。
 * 缓存整表，60 秒过期；写口改完直接 {@link #invalidate()}，同一实例下一次发码即新名单。
 *
 * <p>TTL 兜的是<b>多实例</b>与<b>绕过写口改库</b>（迁移、DBA 手改）：
 * {@code invalidate()} 只清本实例的，另一个实例上要等过期 —— 没有 TTL 就是一直到重启。
 * 与 {@code BannedWordPortImpl} / {@code RolePermResolver} 同一套取舍。
 *
 * <p><b>60 秒是「停用后最坏多久还能登进去」的上限。</b>这一点比禁售词那张表要紧：
 * 那边过期前多拦几个词，这边过期前那把钥匙还能用。但把 TTL 压到 0（每次查库）
 * 换来的只是把这个上限从 60 秒变成 0，代价是给每次发码加一次查库 ——
 * 而真要立刻锁死一个号，删掉它名下的账号会比等这 60 秒快。
 */
@Service
public class OtpTestPhoneServiceImpl implements OtpTestPhoneService {

    private static final Logger log = LoggerFactory.getLogger(OtpTestPhoneServiceImpl.class);

    private static final long TTL_MS = 60_000L;

    /**
     * 启用中的条目上限。
     *
     * <p><b>3 不是随手取的</b>：正常用途是「一个苹果审核号 + 一个自动化回归号」，
     * 留一个余量给交接期。上限存在的理由不是省空间，是让这张表<b>不可能长成一份
     * 通用后门名单</b> —— 一个个加到二十条不会有任何时刻看起来不对。
     */
    private static final int MAX_ENABLED = 3;

    /** 与 {@code AuthServiceImpl.PWD_MIN_LEN} 同档。挡的是「1234」这种 */
    private static final int CODE_MIN_LEN = 6;

    private record Snapshot(List<UsrOtpTestPhone> rows, long loadedAt) {
    }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private final OtpTestPhoneMapper mapper;
    private final IdentityMapper identityMapper;
    private final StaffLoginPhonePort staffLoginPhonePort;
    private final AuditLogPort auditLog;

    public OtpTestPhoneServiceImpl(OtpTestPhoneMapper mapper, IdentityMapper identityMapper,
                                   StaffLoginPhonePort staffLoginPhonePort,
                                   AuditLogPort auditLog) {
        this.mapper = mapper;
        this.identityMapper = identityMapper;
        this.staffLoginPhonePort = staffLoginPhonePort;
        this.auditLog = auditLog;
    }

    // ── 读侧 ────────────────────────────────────────────────────────────────

    @Override
    public Optional<String> fixedCodeFor(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        for (UsrOtpTestPhone row : snapshot()) {
            if (phone.equals(row.getPhone())) {
                return Optional.of(row.getCode());
            }
        }
        return Optional.empty();
    }

    /** 快照里<b>只放启用的</b>：停用那条读侧压根不该看见，判断留在一处 */
    private List<UsrOtpTestPhone> snapshot() {
        Snapshot s = cache.get();
        if (s != null && System.currentTimeMillis() - s.loadedAt() < TTL_MS) {
            return s.rows();
        }
        List<UsrOtpTestPhone> rows = mapper.selectList(Wrappers.<UsrOtpTestPhone>lambdaQuery()
                .eq(UsrOtpTestPhone::getEnabled, true)
                .eq(UsrOtpTestPhone::getDeleted, 0));
        cache.set(new Snapshot(rows, System.currentTimeMillis()));
        return rows;
    }

    @Override
    public void invalidate() {
        cache.set(null);
    }

    // ── 写侧 ────────────────────────────────────────────────────────────────

    @Override
    public List<TestPhoneVO> list() {
        return mapper.selectList(Wrappers.<UsrOtpTestPhone>lambdaQuery()
                        .eq(UsrOtpTestPhone::getDeleted, 0)
                        .orderByAsc(UsrOtpTestPhone::getPhone))
                .stream()
                .map(r -> new TestPhoneVO(r.getId(), r.getPhone(), r.getCode(),
                        Boolean.TRUE.equals(r.getEnabled()), r.getRemark()))
                .toList();
    }

    @Override
    public List<TestPhoneVO> save(String phone, String code, String remark) {
        String p = phone == null ? "" : phone.strip();
        String c = code == null ? "" : code.strip();
        if (!p.matches("1\\d{10}")) {
            throw BizException.of(ErrorCode.OTP_TEST_PHONE_FORMAT);
        }
        if (c.length() < CODE_MIN_LEN) {
            throw BizException.of(ErrorCode.OTP_TEST_PHONE_CODE_TOO_SHORT, CODE_MIN_LEN);
        }

        UsrOtpTestPhone existing = mapper.selectOne(Wrappers.<UsrOtpTestPhone>lambdaQuery()
                .eq(UsrOtpTestPhone::getPhone, p)
                .eq(UsrOtpTestPhone::getDeleted, 0)
                .last("limit 1"));

        /*
         * ★ 最关键的一条护栏。**只在新录时查**：已经在名单里的那条改码/改备注时再查一次，
         * 会把「先录白名单、再用它注册」这个唯一的正常用法自己堵死 ——
         * 注册完之后它就有账号了，从那一刻起连改备注都改不了。
         *
         * 两个登录面都要查，理由见 StaffLoginPhonePort：C 端走 usr_identity，
         * B 端店主/子账号走 mch_account.login_phone，而两边共用同一个 OtpStore。
         */
        if (existing == null && accountExists(p)) {
            throw BizException.of(ErrorCode.OTP_TEST_PHONE_EXISTS_ACCOUNT);
        }

        if (existing == null) {
            long enabled = mapper.selectCount(Wrappers.<UsrOtpTestPhone>lambdaQuery()
                    .eq(UsrOtpTestPhone::getEnabled, true)
                    .eq(UsrOtpTestPhone::getDeleted, 0));
            if (enabled >= MAX_ENABLED) {
                throw BizException.of(ErrorCode.OTP_TEST_PHONE_LIMIT, MAX_ENABLED);
            }
            UsrOtpTestPhone row = new UsrOtpTestPhone();
            row.setPhone(p);
            row.setCode(c);
            row.setEnabled(true);
            row.setRemark(blankToNull(remark));
            row.setTenantNo("MAIN");
            row.setDeleted(0);
            mapper.insert(row);
            after("OTP_TEST_PHONE_ADD", p, "固定码 " + c.length() + " 位");
        } else {
            existing.setCode(c);
            existing.setRemark(blankToNull(remark));
            mapper.updateById(existing);
            after("OTP_TEST_PHONE_UPDATE", p, "固定码 " + c.length() + " 位");
        }
        return list();
    }

    @Override
    public List<TestPhoneVO> setEnabled(Long id, boolean enabled) {
        UsrOtpTestPhone row = require(id);
        if (enabled && !Boolean.TRUE.equals(row.getEnabled())) {
            /*
             * 重新启用要重新过上限与「已存在账号」两道 —— 停用不是删除，
             * 否则「停用 → 那个号注册了店 → 再启用」就绕过了最关键那条护栏。
             */
            if (accountExists(row.getPhone())) {
                throw BizException.of(ErrorCode.OTP_TEST_PHONE_EXISTS_ACCOUNT);
            }
            long on = mapper.selectCount(Wrappers.<UsrOtpTestPhone>lambdaQuery()
                    .eq(UsrOtpTestPhone::getEnabled, true)
                    .eq(UsrOtpTestPhone::getDeleted, 0));
            if (on >= MAX_ENABLED) {
                throw BizException.of(ErrorCode.OTP_TEST_PHONE_LIMIT, MAX_ENABLED);
            }
        }
        row.setEnabled(enabled);
        mapper.updateById(row);
        after("OTP_TEST_PHONE_ENABLED", row.getPhone(), enabled ? "开" : "关");
        return list();
    }

    @Override
    public List<TestPhoneVO> remove(Long id) {
        UsrOtpTestPhone row = require(id);
        // 物理删：唯一键里没有 deleted，软删掉的行仍占着这个号（见 OtpTestPhoneMapper）
        mapper.purge(id);
        after("OTP_TEST_PHONE_REMOVE", row.getPhone(), null);
        return list();
    }

    // ── 私有 ────────────────────────────────────────────────────────────────

    private UsrOtpTestPhone require(Long id) {
        UsrOtpTestPhone row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    /** C 端凭证与 B 端登录号<b>两个面</b>都算。漏一面就等于这条护栏没生效 */
    private boolean accountExists(String phone) {
        long c = identityMapper.selectCount(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.PHONE)
                .eq(UsrIdentity::getIdentityValue, phone));
        return c > 0 || staffLoginPhonePort.isStaffLoginPhone(phone);
    }

    /**
     * 缓存失效 + 审计，<b>顺序固定</b>：先让新名单生效，再记账。
     *
     * <p>{@code critical=true} —— 这里每一行都是一把能登进某个手机号账号的钥匙，
     * 与「封禁商家」同一档。审计本身不抛异常（{@code AuditLogPort} 的口径），
     * 所以放在后面不会让业务回滚。
     */
    private void after(String action, String phone, String detail) {
        invalidate();
        auditLog.record(action, phone, detail, true);
        log.warn("[otp-test-phone] {} {}", action, mask(phone));
    }

    /** 日志里的手机号一律打码：日志会被收集、被转发，它不该成为一份号码库 */
    private static String mask(String phone) {
        return phone == null || phone.length() < 11
                ? "***"
                : phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String blankToNull(String s) {
        String t = s == null ? "" : s.strip();
        return t.isEmpty() ? null : t;
    }
}
