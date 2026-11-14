package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** Service同样校验输入，内部调用不能绕过HTTP约束。 */
public final class CommerceValidation {
    private CommerceValidation() {}

    public static void text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    public static void page(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static long offset(int page, int size) {
        page(page, size);
        return Math.multiplyExact((long) page - 1, size);
    }
}
