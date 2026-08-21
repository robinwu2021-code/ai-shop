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

    /**
     * 生成**图文详情**正文（B 端「自动生成」按钮）。
     *
     * <p>与 {@link #recognize} 分开而不是复用：那个要的是结构化 JSON（填表单字段），
     * 这个要的是一段可以直接贴进详情的中文长文 —— 两者的提示词、token 预算、
     * 失败后该怎么办都不一样。硬塞进一个方法会让两边互相将就。
     *
     * <p><b>结果永远是草稿</b>：模型不知道这家店的真实产地与保质期，
     * 它写出来的是一个结构合理的模板。端上必须把它填进可编辑的输入框，
     * 而不是直接保存 —— 让商家改，比让他从空白开始容易得多。
     *
     * @param imageUrl 商品主图，可为空（没图就只按文字写）
     * @param title    商品名。**必须有** —— 没有名字的话模型只能瞎编
     * @param subtitle 副标题，可为空
     * @param category 类目中文路径，如「食品生鲜 / 水果」，可为空
     * @return null = 没生成出来 / 模型不可达
     */
    String describe(String imageUrl, String title, String subtitle, String category);
}
