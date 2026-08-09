package ai.neargo.shop.spi.platform;

/**
 * 任意域 → platform：读主数据（主体类型 / 行业 / 渠道的规则）。
 *
 * <p>只暴露<b>判断</b>，不暴露表：调用方要的从来不是「这一行长什么样」，
 * 而是「这个主体要不要执照」「这个旧取值对应哪个权威码」。
 * 返回整行的话，各域会顺手用上不该用的列，将来 platform 改一列就要改三个模块。
 *
 * <p><b>为什么这件事必须集中</b>：「PERSONAL 是不是就是小微」这个判断
 * 此前在代码里出现过<b>三次</b>，各写各的 —— 建商家一次、建分账主体一次、
 * 入驻校验一次。判错一次的后果不是显示错误，是商家进件被通道拒。
 */
public interface MasterDataPort {

    /**
     * 把任意主体取值翻译成权威码（通道口径 MICRO / INDIVIDUAL / ENTERPRISE）。
     *
     * <p>存量数据里是 PERSONAL / INDIVIDUAL_BIZ / COMPANY，两套并存期间
     * 一切读写都要先过这里。
     *
     * @return 传入的已是权威码时原样返回；认不出来返回 null
     */
    String canonicalSubject(String anySubject);

    /** 该主体（权威码）是否受行业白名单限制。仅小微为 true。 */
    boolean industryGated(String subjectType);

    /** 该主体（权威码）的结算账户形态：PERSONAL_OPENID / MERCHANT_ID。查不到返回 null。 */
    String settleAccountType(String subjectType);

    /**
     * 支付通道的展示名（{@code sys_pay_channel.name}）。
     *
     * <p>放在这里而不是让端上写死一份：通道改名（"微信支付" → "微信收付通"）时
     * 三端各改一次必然漏一处，而漏掉的那处会长期显示一个不存在的名字。
     *
     * @return 查不到时返回通道码本身，<b>不返回 null</b> —— 页面上宁可显示 WECHAT，
     *         也不要显示一个空白的支付方式
     */
    String channelName(String payChannel);
}
