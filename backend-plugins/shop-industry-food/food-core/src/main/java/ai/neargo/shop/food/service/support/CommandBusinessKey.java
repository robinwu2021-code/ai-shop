package ai.neargo.shop.food.service.support;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;

import java.util.Locale;

/**
 * 行业为主的命令靠业务键防重放（TDD-餐饮域迁移新库 §8-Q1，用户 2026-11-27 定）。
 *
 * <p>业务单号由「操作 + 主体 + 门店 + 操作人 + 请求键」确定性派生：同一个请求重放，得到同一个单号；
 * 行业表在单号上有唯一约束，重放时按单号查回首次结果，不再要永久命令回执。
 * 同一请求键带了不同参数，由各命令拿查回的业务事实与本次请求比对，对不上按冲突拒绝。
 *
 * <p>格式：前缀 + {@code K} + 20 位十六进制摘要（大写），总长不超过 32。前缀沿用随机单号的前缀，
 * 带 {@code K} 只是让人一眼看出这是请求派生的号。
 */
public final class CommandBusinessKey {
    private static final int DIGEST = 20;

    private CommandBusinessKey() {}

    public static String derive(
            String prefix, String operation, String entity, String store, String actor, String key) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String digest =
                FoodCommandFingerprint.hash("command-key:v1", operation, entity, store, actor, key)
                        .substring(0, DIGEST)
                        .toUpperCase(Locale.ROOT);
        String no = prefix + "K" + digest;
        if (no.length() > 32) throw new IllegalArgumentException("Business key prefix too long");
        return no;
    }
}
