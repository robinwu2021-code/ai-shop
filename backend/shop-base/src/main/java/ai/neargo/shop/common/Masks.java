package ai.neargo.shop.common;

/**
 * 脱敏口径的**唯一实现**。
 *
 * <p>此前它散成三份各写各的：{@code UserVO} 的手机号、{@code AddressVO} 的收件人号码、
 * 进件服务的结算账号。**三份实现意味着三种口径** —— 而口径不一致时，
 * 看到的人第一反应是「其中一处泄了」，然后要逐个去读代码才能确认没泄。
 *
 * <p>两条规则：
 * <ul>
 *   <li><b>手机号留头三尾四</b>（138****8000）：本人要能认出是不是自己的号</li>
 *   <li><b>账号类只留尾四</b>（****8000）：银行卡与二级商户号都没有「认出自己」这个需求，
 *       多留一位就是多一位泄露面</li>
 * </ul>
 *
 * <p>空值返回 {@code null} 而不是空串：前端拿到 null 才知道「没有这个值」，
 * 拿到空串会渲染成一个空的字段，看着像数据丢了。
 */
public final class Masks {

    private Masks() {
    }

    /** 手机号：留头三尾四。位数不够时退化成 {@link #tail(String)}，不抛错。 */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        return s.length() < 8 ? tail(s) : s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    /**
     * 邮箱：本地部分留头一尾一，域名不动（{@code r***n@neargo.ai}）。
     *
     * <p><b>域名不遮</b>：遮了就分不清「发到公司邮箱」还是「发到个人邮箱」，
     * 而排查发送记录时这恰恰是第一个要看的。本地部分留两头，本人能认出是不是自己。
     */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int at = s.indexOf('@');
        if (at <= 0) {
            return tail(s);      // 不是邮箱形状时退化，不抛错
        }
        String local = s.substring(0, at);
        String domain = s.substring(at);
        if (local.length() <= 2) {
            return "*".repeat(local.length()) + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    /**
     * 账号类：只留尾四位。
     *
     * <p>银行卡号、二级商户号共用它 —— 两处用不同口径，
     * 会让看到的人以为其中一处泄了更多。
     */
    public static String tail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        return s.length() <= 4 ? "****" : "****" + s.substring(s.length() - 4);
    }
}
