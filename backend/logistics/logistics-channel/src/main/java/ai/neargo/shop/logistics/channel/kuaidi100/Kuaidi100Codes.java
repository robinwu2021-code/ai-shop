package ai.neargo.shop.logistics.channel.kuaidi100;

import ai.neargo.shop.logistics.capability.ChannelOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 快递100 订阅返回码分类与推送验签（官方「快递信息订阅推送」文档，2026-10-09 核对）。
 *
 * <p>分类决定重试策略，所以每个码都要有归属；<b>认不出的码按不可重试</b>：盲目重试会烧额度，原文就是补这张表的依据。
 */
final class Kuaidi100Codes {

    private Kuaidi100Codes() {
    }

    /**
     * 200 提交成功 · 501 重复订阅（仍在跟踪）= 成功 · 500 服务器错误 → 可重试 ·
     * 502 敏感词 / 600 非法订阅者（key 错或没余额）/ 601 key 过期（没余额）/ 700 不支持的公司 /
     * 701 订阅数据错（单号空或超长、回调地址不合法）/ 702 认不出公司 → 不可重试。
     */
    static ChannelOutcome classify(String returnCode, String message) {
        String c = returnCode == null ? "" : returnCode.trim();
        return switch (c) {
            case "200" -> ChannelOutcome.ok("200", null);
            // 重复订阅：同一单号已经在跟踪了 —— 不当成功的话，重试一次反而记成失败
            case "501" -> ChannelOutcome.ok("501", null);
            case "500" -> ChannelOutcome.retryable("500", message);
            case "502", "600", "601", "700", "701", "702" -> ChannelOutcome.fatal(c, message);
            default -> ChannelOutcome.fatal(c.isEmpty() ? "?" : c, "认不出的返回码：" + message);
        };
    }

    /** 推送签名：upper(md5(param + salt)) */
    static String sign(String param, String salt) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest((param + salt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
