package ai.neargo.inventory.api;

/**
 * 进销存自己的错误码。
 *
 * <p><b>为什么不用调用方的错误码</b>：进销存是独立服务（ADR-046），调用方不止 ai-shop。
 * 码在这里定义，文案键用 {@code inventory.*} 命名空间；各调用方在自己的边界上翻译 ——
 * ai-shop 过渡期内嵌时由 {@code shop-inventory} 的切面翻回商城原来的错误码，
 * 前端看到的码与改造前逐个相同。
 *
 * <p>{@link #httpStatus()} 给独立部署的 HTTP 层用；领域代码只抛码，不关心状态码。
 */
public enum InventoryError {

    BAD_REQUEST("inventory.bad_request", 400),
    UNAUTHORIZED("inventory.unauthorized", 401),
    FORBIDDEN("inventory.forbidden", 403),
    NOT_FOUND("inventory.not_found", 404),
    CONFLICT("inventory.conflict", 409),
    /** 可用量不足。参数是缺货的物料号（逗号分隔）。 */
    STOCK_SHORT("inventory.stock.short", 409),
    /** 同一库位已有进行中的盘点单。 */
    COUNT_ALREADY_OPEN("inventory.count.already_open", 409),
    /** 业主没开通这个能力包（档案 capabilities）。 */
    CAPABILITY_DISABLED("inventory.capability.disabled", 403),
    /** 凭证所属系统不在本套部署的放行范围（实例放行，AC-28）。 */
    SYSTEM_NOT_SERVED("inventory.instance.system_not_served", 403),
    /** 这本账的写入方不是当前进程（过渡期内嵌 / 独立二选一，AC-19）。 */
    NOT_WRITER("inventory.owner.not_writer", 409),
    /** 同一个幂等键带着不同的请求再来（AC-03）。 */
    IDEMPOTENCY_CONFLICT("inventory.command.conflict", 409),
    /** 业态档案不合法。参数是第一条违反的规则。 */
    PROFILE_INVALID("inventory.profile.invalid", 400),
    INTERNAL("inventory.internal", 500);

    private final String key;
    private final int httpStatus;

    InventoryError(String key, int httpStatus) {
        this.key = key;
        this.httpStatus = httpStatus;
    }

    /** 文案键，三语文案由各展示方维护。 */
    public String key() {
        return key;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
