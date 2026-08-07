package ai.neargo.shop.settle;

/**
 * 分账通道（微信支付分账 API 的抽象，ADR-002）。
 *
 * <p>抽成接口是为了让 S4 接真通道时**只换这一个实现**：
 * 幂等（{@code requestNo} 唯一）、状态机、重试计数都在 {@link SettleServiceImpl} 里，
 * 与通道无关。
 */
public interface SplitGateway {

    /**
     * @param requestNo 平台侧幂等号
     * @return 是否成功；失败时调用方置 RETRYING/MANUAL，**绝不继续退款**
     */
    Result split(String subOrderNo, long amountMinor, String requestNo);

    Result reverse(String subOrderNo, long amountMinor, String requestNo);

    record Result(boolean success, String providerNo, String message) {

        public static Result ok(String providerNo) {
            return new Result(true, providerNo, null);
        }

        public static Result fail(String message) {
            return new Result(false, null, message);
        }
    }
}
