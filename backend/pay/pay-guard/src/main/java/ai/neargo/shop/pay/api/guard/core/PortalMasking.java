package ai.neargo.shop.pay.api.guard.core;

import java.util.regex.Pattern;

/**
 * 请求体进记录前的脱敏（安全规范 §6）：JSON 里键名像密钥 / 凭证 / 个人信息的值一律替成 {@code ***}，不论嵌套多深。
 * 只做按键名的替换，不解析业务 —— 记录是给人排查的，不需要也不应该有原文。
 */
final class PortalMasking {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(\"(?:[A-Za-z]*(?:secret|password|token|sign|key|card|cvv|pin|idNumber|account|mobile|phone|email|openId))\"\\s*:\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)", Pattern.CASE_INSENSITIVE);

    private PortalMasking() {
    }

    static String mask(String json) {
        return SENSITIVE.matcher(json).replaceAll("$1\"***\"");
    }
}
