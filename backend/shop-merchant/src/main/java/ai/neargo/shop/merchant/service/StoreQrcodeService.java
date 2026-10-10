package ai.neargo.shop.merchant.service;

import ai.neargo.shop.common.PageData;

/**
 * 店铺码档案（P-10.1.3）：码本身 + 被扫了多少次 + 印了多少张。
 *
 * <p>三个数来自三个地方：码在 {@code mch_store.store_code}（V298 前在主体上），
 * 扫码次数在埋点域（走 Port，兄弟模块够不着），印刷量是**线下事实**由运营录入。
 *
 * <p><b>列表包含还没有码的门店</b>（V298）。此前只列有码的，于是「这家分店根本没发过码」
 * 这件事在运营端看不见 —— 而那正是运营最需要动手的一行。
 */
public interface StoreQrcodeService {

    /**
     * 店铺码列表，<b>一行一家门店</b>。
     *
     * @param from       扫码次数的统计区间起（毫秒，含）
     * @param to         止（毫秒，含）
     * @param codeless   true = 只看还没发码的门店（运营要动手的那批）
     */
    PageData<QrcodeRow> list(String keyword, long from, long to, boolean codeless, long page, long size);

    /**
     * 给这家门店发码；<b>已经有码就原样返回</b>（幂等，重复点不会换码）。
     *
     * @return 这家店的码
     */
    String issue(String merchantNo, String storeNo, String operatorNo);

    /**
     * <b>换码：旧码当场失效。</b>
     *
     * <p>已经印出去贴在店里的物料会全部变成死链 —— 这是线下成本，不是一次界面操作。
     * 所以必须给理由并留痕：事后要能回答「谁在什么时候把哪家店的码换了、为什么」。
     *
     * @param reason 换码原因，必填
     * @return 新码
     */
    String reissue(String merchantNo, String storeNo, String reason, String operatorNo);

    /**
     * 登记一次印刷。
     *
     * @param qty <b>有符号</b>：印多了冲减就传负数，补一行而不是改历史行
     */
    void recordPrint(String merchantNo, String storeNo, int qty, String size, String remark,
                     String operatorNo);

    /**
     * 导出用的一行：在列表的基础上带上<b>可直接印的码图</b>。
     *
     * <p>为什么单开一个方法而不是给 list 加个开关：取码图会调微信永久码接口，
     * 而那有额度。列表是每次翻页都调的，导出是运营明确点一次的 —— 两者的代价不同，
     * 混在一个方法里迟早有人给列表传上 true。
     */
    java.util.List<ExportRow> exportRows(String keyword, long from, long to, boolean codeless, long limit);

    /**
     * @param storeNo     哪家门店。<b>一行一店</b>，不再是一行一主体
     * @param code        这家店的码；<b>null = 还没发过码</b>，不是空串
     * @param size        最近一次印刷的尺寸；<b>从没印过是 null</b>
     * @param printed     累计印量；<b>从没登记过是 null 而不是 0</b> ——
     *                    「没登记」与「印了 0 张」是两件事，混成一个数之后
     *                    运营没法知道该去催谁登记
     * @param scanCount   区间内扫码次数。**这个可以是 0** —— 埋点一直在记，
     *                    0 就是真的没人扫（与 printed 的 null 不同）
     */
    record QrcodeRow(String merchantNo, String merchantName, String storeNo, String storeName,
                     String communityName, String code, String size, Integer printed,
                     long scanCount) {
    }

    /**
     * @param imageBase64 小程序码 PNG 的 base64（不含 {@code data:} 前缀）。
     *                    <b>null = 这家店取不到码图</b>（没发码，或微信通道没开）——
     *                    导出里留空，不塞占位图：占位图会被直接送去印刷
     */
    record ExportRow(QrcodeRow row, String imageBase64) {
    }
}
