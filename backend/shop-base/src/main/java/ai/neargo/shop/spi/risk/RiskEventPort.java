package ai.neargo.shop.spi.risk;

/**
 * 任意域 → risk：上报一次风控命中。
 *
 * <p><b>方向是「生产方推给风控」，不是「风控去各域查」。</b> 反过来的话，
 * 风控要认识每一个可能出风险的域，而那些域改一个列名，风控就跟着炸 ——
 * 炸的时候没人会想到是风控。
 *
 * <p>目前唯一的调用方是归因引擎（P-16.2.2 异常裂变：同设备 / 同 IP）——
 * 只有归因链路知道「这个人是被谁、从哪台设备带进来的」。
 * 交易与售后那两类走 Outbox 事件，不经过这个 Port（消费方自己订阅，生产方一行不改）。
 *
 * <p><b>实现必须幂等</b>：同一个 {@code evidenceRef} 重复上报只计一次。
 * 调用方可能在重试链路上，而「多计一次」的后果是把正常用户送进黑名单。
 */
public interface RiskEventPort {

    String FAKE_ORDER = "FAKE_ORDER";
    String ABNORMAL_FISSION = "ABNORMAL_FISSION";
    String MALICIOUS_REFUND = "MALICIOUS_REFUND";

    String SUBJECT_USER = "USER";
    String SUBJECT_MERCHANT = "MERCHANT";
    String SUBJECT_DEVICE = "DEVICE";

    /**
     * 记一次命中；累计达到该类型的阈值时开（或追加到）一张风险事件。
     *
     * @param type         {@link #FAKE_ORDER} / {@link #ABNORMAL_FISSION} / {@link #MALICIOUS_REFUND}
     * @param subjectType  主体类型
     * @param subject      主体标识（userNo / entityNo / 设备号或 IP）。<b>不是昵称</b> ——
     *                     昵称会改、会重名，按它拉黑等于按一个随时会变的字符串封人
     * @param subjectName  展示名，可空
     * @param evidenceRef  证据单号（订单号 / 售后单号 / 归因链路号）。<b>幂等键</b>
     * @param detail       这一次命中的人话说明，运营要能直接读
     * @return 命中之后是否已经存在待处置事件（调用方可据此决定要不要在自己的留痕上打标）
     */
    boolean hit(String type, String subjectType, String subject, String subjectName,
                String evidenceRef, String detail);

    /**
     * 主体当前是否在生效中的黑名单里。
     *
     * <p>⚠️ 这一版**没有任何生产调用方**：拦截规则先做成配置 + 事件记录，
     * 实际拦截点另说（TDD-运营端风控域 §二 D3）。留这个方法是为了让接拦截点的人
     * 有一个明确的入口，而不是各自去查表。
     */
    boolean blocked(String subjectType, String subject);
}
