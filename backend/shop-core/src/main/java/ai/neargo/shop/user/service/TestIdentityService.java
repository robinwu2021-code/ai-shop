package ai.neargo.shop.user.service;

import java.util.List;

/**
 * 测试身份白名单（TDD-测试身份白名单登录）：名单里的手机号 / 邮箱用统一验证码、统一密码登录注册。
 *
 * <p>统一码与统一密码只在服务器配置（{@code shop.auth.test-identity.code / .password}），
 * 总开关 {@code shop.auth.test-identity.enabled} 默认关；关着时所有判断恒为 false，行为与没有这个功能完全一致。
 */
public interface TestIdentityService {

    String PHONE = "PHONE";
    String EMAIL = "EMAIL";

    record TestIdentityVO(Long id, String identityType, String identityValue, boolean enabled, String remark) {
    }

    /** 开关打开且身份在启用名单里 */
    boolean isTestIdentity(String type, String value);

    /** 命中名单且 code 等于统一验证码 */
    boolean acceptsCode(String type, String value, String code);

    /** 命中名单且 password 等于统一密码 */
    boolean acceptsPassword(String type, String value, String password);

    /** 总开关 */
    boolean enabled();

    List<TestIdentityVO> list();

    List<TestIdentityVO> save(String type, String value, String remark);

    List<TestIdentityVO> setEnabled(Long id, boolean enabled);

    List<TestIdentityVO> remove(Long id);

    /** 规范化：手机号去空格与连字符；邮箱 trim + 小写。格式不对返回 null */
    static String normalize(String type, String value) {
        if (value == null) return null;
        if (PHONE.equals(type)) {
            String v = value.replaceAll("[\\s-]", "");
            return v.matches("\\+?\\d{6,20}") ? v : null;
        }
        if (EMAIL.equals(type)) {
            String v = value.trim().toLowerCase(java.util.Locale.ROOT);
            return v.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") && v.length() <= 128 ? v : null;
        }
        return null;
    }
}
