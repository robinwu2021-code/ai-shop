package ai.neargo.shop.pay.setting;

/**
 * 支付域设置的读写。<b>与搬家前的 {@code SettingPort} 同形状</b>，
 * 只是表换成了 pay 自己的 —— 迁移期看不出区别，这是有意的。
 *
 * <h2>四个 key</h2>
 * <pre>
 * points.client.policy    端积分策略 —— 哪个端能发/能用积分
 * points.config           积分配置 —— 汇率、有效期、兜底比例
 * finance.tax-rule        个税代扣规则 —— 提现时扣多少
 * finance.invoice-title   平台开票信息 —— 供应商照着它开票
 * </pre>
 */
public interface PaySettingService {

    /**
     * @param defaultJson 没配过时返回它。<b>不返回 null</b> ——
     *                    调用方一律 parse，null 会变成一处 NPE，
     *                    而那处 NPE 出现在「运营还没配过」这种最正常的状态下
     */
    String get(String key, String defaultJson);

    /** @param operatorNo 留痕：这四个设置每一个改动都会影响钱怎么算 */
    void put(String key, String json, String operatorNo);
}
