package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.user.entity.UsrTestIdentity;
import ai.neargo.shop.user.mapper.UserMappers.TestIdentityMapper;
import ai.neargo.shop.user.service.TestIdentityService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 测试身份白名单（TDD-测试身份白名单登录）。2026-11-07 用户决定：手机号或邮箱加入白名单后用统一验证码、统一密码，
 * 统一码与密码直接写在服务器配置里、所有白名单身份一致；已注册的真实账号（如 robin@neargo.ai）也可以加入。
 *
 * <p>护栏：总开关默认关；prod profile 下打开拒绝启动（TestIdentityGuard）；名单由运营维护并审计，启用上限 10 条；
 * 发码与密码登录的限流照旧；比较用常量时间。
 */
@Service
public class TestIdentityServiceImpl implements TestIdentityService {

    private static final Logger log = LoggerFactory.getLogger(TestIdentityServiceImpl.class);
    static final int MAX_ENABLED = 10;
    private static final long TTL_MS = 60_000L;

    private record Snapshot(List<UsrTestIdentity> rows, long loadedAt) {
    }

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();
    private final TestIdentityMapper mapper;
    private final AuditLogPort auditLog;
    private final boolean enabled;
    private final String code;
    private final String password;

    public TestIdentityServiceImpl(TestIdentityMapper mapper, AuditLogPort auditLog,
                                   @Value("${shop.auth.test-identity.enabled:false}") boolean enabled,
                                   @Value("${shop.auth.test-identity.code:}") String code,
                                   @Value("${shop.auth.test-identity.password:}") String password) {
        this.mapper = mapper;
        this.auditLog = auditLog;
        this.enabled = enabled;
        this.code = code == null ? "" : code.trim();
        this.password = password == null ? "" : password;
        if (enabled && (this.code.isEmpty() || this.password.isEmpty())) {
            throw new IllegalStateException("shop.auth.test-identity.enabled=true 时必须配置 .code 与 .password");
        }
        if (enabled) {
            log.warn("[test-identity] 测试身份白名单已开启：名单内手机号 / 邮箱可用统一验证码、统一密码登录。上线运营前关闭");
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean isTestIdentity(String type, String value) {
        if (!enabled) return false;
        String v = TestIdentityService.normalize(type, value);
        if (v == null) return false;
        return snapshot().stream().anyMatch(r -> type.equals(r.getIdentityType()) && v.equals(r.getIdentityValue()));
    }

    @Override
    public boolean acceptsCode(String type, String value, String given) {
        return isTestIdentity(type, value) && same(code, given == null ? null : given.trim());
    }

    @Override
    public boolean acceptsPassword(String type, String value, String given) {
        return isTestIdentity(type, value) && same(password, given);
    }

    private static boolean same(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private List<UsrTestIdentity> snapshot() {
        Snapshot s = cache.get();
        if (s != null && System.currentTimeMillis() - s.loadedAt() < TTL_MS) return s.rows();
        List<UsrTestIdentity> rows = mapper.selectList(Wrappers.<UsrTestIdentity>lambdaQuery()
                .eq(UsrTestIdentity::getEnabled, true).eq(UsrTestIdentity::getDeleted, 0));
        cache.set(new Snapshot(rows, System.currentTimeMillis()));
        return rows;
    }

    @Override
    public List<TestIdentityVO> list() {
        return mapper.selectList(Wrappers.<UsrTestIdentity>lambdaQuery().eq(UsrTestIdentity::getDeleted, 0)
                        .orderByAsc(UsrTestIdentity::getIdentityType).orderByAsc(UsrTestIdentity::getIdentityValue))
                .stream().map(r -> new TestIdentityVO(r.getId(), r.getIdentityType(), r.getIdentityValue(),
                        Boolean.TRUE.equals(r.getEnabled()), r.getRemark())).toList();
    }

    @Override
    public List<TestIdentityVO> save(String type, String value, String remark) {
        String v = TestIdentityService.normalize(type, value);
        if (v == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        UsrTestIdentity existing = mapper.selectOne(Wrappers.<UsrTestIdentity>lambdaQuery()
                .eq(UsrTestIdentity::getIdentityType, type).eq(UsrTestIdentity::getIdentityValue, v)
                .eq(UsrTestIdentity::getDeleted, 0).last("limit 1"));
        if (existing == null) {
            requireCapacity();
            UsrTestIdentity row = new UsrTestIdentity();
            row.setIdentityType(type);
            row.setIdentityValue(v);
            row.setEnabled(true);
            row.setRemark(blankToNull(remark));
            row.setTenantNo("MAIN");
            row.setDeleted(0);
            mapper.insert(row);
            after("TEST_IDENTITY_ADD", type, v);
        } else {
            existing.setRemark(blankToNull(remark));
            mapper.updateById(existing);
            after("TEST_IDENTITY_UPDATE", type, v);
        }
        return list();
    }

    @Override
    public List<TestIdentityVO> setEnabled(Long id, boolean on) {
        UsrTestIdentity row = require(id);
        if (on && !Boolean.TRUE.equals(row.getEnabled())) requireCapacity();
        row.setEnabled(on);
        mapper.updateById(row);
        after(on ? "TEST_IDENTITY_ENABLE" : "TEST_IDENTITY_DISABLE", row.getIdentityType(), row.getIdentityValue());
        return list();
    }

    @Override
    public List<TestIdentityVO> remove(Long id) {
        UsrTestIdentity row = require(id);
        mapper.purge(id);
        after("TEST_IDENTITY_REMOVE", row.getIdentityType(), row.getIdentityValue());
        return list();
    }

    private void requireCapacity() {
        long on = mapper.selectCount(Wrappers.<UsrTestIdentity>lambdaQuery()
                .eq(UsrTestIdentity::getEnabled, true).eq(UsrTestIdentity::getDeleted, 0));
        if (on >= MAX_ENABLED) throw BizException.of(ErrorCode.OTP_TEST_PHONE_LIMIT, MAX_ENABLED);
    }

    private UsrTestIdentity require(Long id) {
        UsrTestIdentity row = id == null ? null : mapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getDeleted())) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private void after(String action, String type, String value) {
        cache.set(null);
        auditLog.record(action, type + ":" + mask(type, value), "测试身份白名单", true);
        log.warn("[test-identity] {} {}", action, mask(type, value));
    }

    private static String mask(String type, String v) {
        if (v == null) return "***";
        if (EMAIL.equals(type)) {
            int at = v.indexOf('@');
            return at <= 1 ? "***" + v.substring(Math.max(at, 0)) : v.charAt(0) + "***" + v.substring(at);
        }
        return v.length() <= 4 ? "***" : "***" + v.substring(v.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
