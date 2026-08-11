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
     * @param payMerchantNo 收款商户号 —— <b>钱打给谁</b>。
     *                      在此之前这个接口没有这个参数，通道压根不知道收款方是谁；
     *                      单店时能跑通只是因为一个主体只有一个号，收款方是通道实现里的
     *                      隐含默认。多门店放开后这个默认是错的。
     *                      <b>取值必须来自结算单上的快照</b>，不是「这家店现在用哪个号」——
     *                      商家改号之后，还没打的历史流水仍要打进当初收款的那个账户
     * @param requestNo     平台侧幂等号
     * @return 是否成功；失败时调用方置 RETRYING/MANUAL，**绝不继续退款**
     */
    Result split(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo);

    /**
     * 冲正。<b>必须打回当初收款的那个账户</b>，同样取自结算单快照。
     *
     * <p>不这么做的后果：商家上个月改了门店收款号，这个月发生一笔上个月订单的退款，
     * 钱从新账户扣 —— 新账户里可能根本没有那么多余额，退款失败；就算成功了，
     * 两个账户的账也各错一笔，且方向相反。
     */
    Result reverse(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo);

    /**
     * 积分补差：平台把买家用积分抵掉的那部分<b>补进</b>二级商户账户。
     *
     * <p>方向与 {@link #split} 相反 —— split 是从二级商户账户往外拿平台应收，
     * 补差是往里放钱。所以顺序上必须先补后分：先分的话账户余额可能不够扣，
     * 分账被通道拒绝，而那时订单已经付过款了。
     *
     * <p>通道侧本来就有这一步（微信 {@code /v3/ecommerce/subsidies}），
     * {@code PayGateway.subsidy} 与两个通道实现也一直都在 —— 缺的只是调用方。
     */
    Result subsidy(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo);

    /** 补差回退：退款时把补贴部分收回平台。 */
    Result subsidyReturn(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo);

    record Result(boolean success, String providerNo, String message) {

        public static Result ok(String providerNo) {
            return new Result(true, providerNo, null);
        }

        public static Result fail(String message) {
            return new Result(false, null, message);
        }
    }
}
