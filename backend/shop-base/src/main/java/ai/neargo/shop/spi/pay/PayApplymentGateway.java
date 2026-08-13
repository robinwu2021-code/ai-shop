package ai.neargo.shop.spi.pay;

/**
 * 收款进件网关：把一个经营主体在支付通道开成「二级商户」。
 *
 * <p><b>为什么与交易网关（{@code PayGateway}）分开</b>：两者生命周期完全不同。
 * 交易网关上的补差/分账/退款在时序上互相约束，必须放在一起；
 * 进件是<b>一次性开户</b>，只与「资料齐不齐、通道批不批」有关，
 * 和某一笔订单没有任何关系。合成一个接口会让实现类既管开户又管资金，
 * 而这两件事的失败处理完全不同 —— 开户失败是补资料重提，资金失败是要对账。
 *
 * <p><b>进件是异步的</b>：提交后通道返回一个申请单号，真正的结果（通过/驳回）
 * 稍后才来。所以本接口只有「提交」和「查结果」，没有「同步开户」——
 * 假装它是同步的，就会写出「提交成功 = 能收款了」的代码，
 * 而商家第一笔订单才发现收不了钱。
 *
 * <p><b>本接口不碰密钥</b>：证书与私钥由部署环境注入，实现类只从配置读引用。
 */
public interface PayApplymentGateway {

    /** 这个实现对应哪个通道，与 {@code sys_pay_channel.pay_channel} 同值。 */
    String payChannel();

    /**
     * 提交进件申请。
     *
     * @param cmd 进件资料
     * @return 通道侧的申请单号（{@code channel_apply_no}），用于后续查询与回调对账
     */
    String submit(SubmitCommand cmd);

    /**
     * 查询进件结果。
     *
     * <p>回调可能丢、可能乱序，所以<b>查询是权威</b>：收到回调也要回查一次再落库。
     * 只信回调的系统，在通道重推历史消息时会把已经开好的户改回「审核中」。
     */
    ApplymentResult query(String channelApplyNo);

    /**
     * 进件资料。
     *
     * @param entityNo           经营主体
     * @param entityName         主体名称（与营业执照一致，通道会核对）
     * @param legalForm          法律形态 MICRO/INDIVIDUAL/ENTERPRISE，决定通道要什么材料
     * @param contactName        联系人
     * @param contactPhone       联系电话
     * @param licenses           资质图（营业执照/身份证）的可访问地址
     * @param settleAccountType  结算账户形态 PERSONAL_BANK_CARD / MERCHANT_ID
     * @param settleAccount      结算账号**明文**。<b>只在本次调用中存在</b> ——
     *                           不落库、不进日志，库里只留 {@code settle_account_masked}
     */
    record SubmitCommand(String entityNo, String entityName, String legalForm,
                         String contactName, String contactPhone,
                         java.util.List<String> licenses,
                         String settleAccountType, String settleAccount) {
    }

    /**
     * 进件结果。
     *
     * @param status      APPLYING / ACTIVE / REJECTED，与 {@code mch_payment_merchant.apply_status} 同值域
     * @param subMchid    通过时的二级商户号；未通过为空
     * @param rejectReason 驳回原因。<b>驳回必须带原因</b> —— 没有原因商家只能反复重提
     */
    record ApplymentResult(String status, String subMchid, String rejectReason) {
    }
}
