package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/**
 * 进销存错误码 → 商城错误码。
 *
 * <p><b>一一对应、逐个与改造前相同</b>：进销存搬出 ai-shop 之前，领域代码抛的就是这些商城码，
 * B 端按码显示文案、调用方按码分支（例：缺货 {@code STOCK_SHORT_ITEMS} 决定下单失败的提示）。
 * 翻译错一个，界面不报错、只是提示变了 —— 所以表里每个枚举值都必须有去处，
 * {@code switch} 不写 default，新增码时编译期就会漏不过去。
 */
public final class InventoryErrors {

    private InventoryErrors() {
    }

    public static ErrorCode toShop(InventoryError error) {
        return switch (error) {
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case CONFLICT -> ErrorCode.CONFLICT;
            case STOCK_SHORT -> ErrorCode.STOCK_SHORT_ITEMS;
            case COUNT_ALREADY_OPEN -> ErrorCode.COUNT_ALREADY_OPEN;
            case INTERNAL -> ErrorCode.INTERNAL_ERROR;
        };
    }

    public static BizException toShop(InventoryException e) {
        BizException translated = BizException.of(toShop(e.error()), e.args());
        translated.initCause(e);
        return translated;
    }
}
