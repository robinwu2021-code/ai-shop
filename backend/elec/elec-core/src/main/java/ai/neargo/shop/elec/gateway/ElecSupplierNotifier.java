package ai.neargo.shop.elec.gateway;

/**
 * 告诉供应商：有新的求购派给你了 / 你的报价被选中了。
 *
 * <p>供应商与买家是<b>同一个账号体系</b>（小程序登录的那个号），所以走的也是同一条路：
 * 站内信（必达的记录）+ 微信订阅消息（加速通道）。两样都在主系统。
 *
 * <p><b>失败不抛</b>：通知送不到不该让派单回滚。调用方据返回值写 notified_at ——
 * 没送到的那几条在库里查得出来，而不是只在日志里。
 */
public interface ElecSupplierNotifier {

    /**
     * 有新的求购。<b>一家一条</b>，不按行发 —— 一张 BOM 派给他五行，他要的是「有新求购」一条。
     *
     * @param lineCnt 这一单派给他几行
     */
    boolean newDispatch(String accountRef, String supplierNo, int lineCnt);

    /** 你的报价被买家选中了 */
    boolean quoteAccepted(String accountRef, String quoteNo, long qty);
}
