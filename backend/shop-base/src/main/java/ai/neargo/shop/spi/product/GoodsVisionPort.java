package ai.neargo.shop.spi.product;

import java.util.Map;

/**
 * 拍照建品的**视觉识别**（B-11.3.7）。
 *
 * <p>做成端口而不是让商品域直接调模型：识别是一个**外部适配**（与支付通道、短信同类），
 * 换模型、换供应商、断网降级都不该让商品域跟着改。
 * 实现在 {@code shop-channel}（`GoodsVisionGateway`），核心只认这个接口。
 *
 * <p><b>识别永远是锦上添花</b>：调用它的那一刻，主图已经上传成功了。
 * 所以失败一律返回 {@code null} 而不是抛异常 —— 模型不可达不该让「拍照设主图」跟着失败。
 */
public interface GoodsVisionPort {

    /** 没配模型时返回 false，调用方可以据此省掉一次必然为空的往返 */
    boolean isEnabled();

    /**
     * 看图猜商品。
     *
     * @param imageUrl   公开可访问的图片 URL —— 商品图落在公开桶，模型侧要能直接拉到
     * @param categories 候选类目「编号 → 中文路径」。**必须给**：不给的话模型会返回
     *                   「日用品」这种不存在的编号，而查无此项的 categoryNo 落进草稿后，
     *                   商家要到点保存那一刻才撞上类目校验
     * @return null = 没识别出来 / 模型不可达
     */
    Guess recognize(String imageUrl, Map<String, String> categories);

    /**
     * 识别结果。**全部是建议值** —— 端上按 confidence 决定预填还是只提示，
     * 店主决定留不留。
     *
     * @param categoryNo 已按候选表校验过：模型给的编号不在表里时是空串
     */
    record Guess(String title, String subtitle, String type, String categoryNo, double confidence) {
    }
}
