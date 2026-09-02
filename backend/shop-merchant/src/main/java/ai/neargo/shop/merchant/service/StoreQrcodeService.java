package ai.neargo.shop.merchant.service;

import ai.neargo.shop.common.PageData;

/**
 * 店铺码档案（P-10.1.3）：码本身 + 被扫了多少次 + 印了多少张。
 *
 * <p>三个数来自三个地方：码在 {@code mch_entity.store_code}，
 * 扫码次数在埋点域（走 Port，兄弟模块够不着），印刷量是**线下事实**由运营录入。
 */
public interface StoreQrcodeService {

    /**
     * 店铺码列表。
     *
     * @param from 扫码次数的统计区间起（毫秒，含）
     * @param to   止（毫秒，含）
     */
    PageData<QrcodeRow> list(String keyword, long from, long to, long page, long size);

    /**
     * 登记一次印刷。
     *
     * @param qty <b>有符号</b>：印多了冲减就传负数，补一行而不是改历史行
     */
    void recordPrint(String merchantNo, int qty, String size, String remark, String operatorNo);

    /**
     * @param size        最近一次印刷的尺寸；<b>从没印过是 null</b>
     * @param printed     累计印量；<b>从没登记过是 null 而不是 0</b> ——
     *                    「没登记」与「印了 0 张」是两件事，混成一个数之后
     *                    运营没法知道该去催谁登记
     * @param scanCount   区间内扫码次数。**这个可以是 0** —— 埋点一直在记，
     *                    0 就是真的没人扫（与 printed 的 null 不同）
     */
    record QrcodeRow(String merchantNo, String merchantName, String communityName,
                     String code, String size, Integer printed, long scanCount) {
    }
}
