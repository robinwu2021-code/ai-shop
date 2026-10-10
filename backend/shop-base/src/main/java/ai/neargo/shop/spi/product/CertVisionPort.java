package ai.neargo.shop.spi.product;

/**
 * 证照识别：上传营业执照 / 身份证，自动读出结构化字段。
 *
 * <p>与 {@link GoodsVisionPort} 是同一类外部适配（都走视觉模型），但有一条**关键差别**：
 *
 * <h2>入参是字节，不是 URL</h2>
 *
 * <p>{@code GoodsVisionPort} 收的是**公开可访问的 URL** —— 商品图本来就摆在公开桶里给买家看，
 * 让模型侧直接去拉没有代价。<b>证照不行</b>：身份证照片进了公开桶，
 * 任何人拿到那个 URL 就能看到完整的姓名、住址与身份证号，而 URL 是会被日志、
 * 转发、浏览器历史带到各处的。
 *
 * <p>所以这里收字节、内联成 data URI 发给模型，<b>图片自始至终不落公开存储</b>。
 * 代价是请求体变大（一张压到 1600px 的执照约 400KB，base64 后约 530KB），
 * 换来的是「这张图除了模型谁也没见过」。
 *
 * <h2>识别结果是建议，不是事实</h2>
 *
 * <p>调用方**必须**把结果交给人确认再落库。模型今天准，不代表每一张都准 ——
 * 而营业执照上的统一社会信用代码错一位，后面进件、开票、对账会一路错下去，
 * 且没有任何一步会报错。这个端口只负责「省掉手打」，不负责「保证正确」。
 *
 * <p>失败一律返回 {@code null}：识别不可用不该让上传本身失败。
 */
public interface CertVisionPort {

    /** 没配模型时返回 false，调用方可以据此连图都不用读 */
    boolean isEnabled();

    /**
     * 认一张证照。
     *
     * @param imageBytes  图片字节。<b>调用方负责先压到合理尺寸</b> ——
     *                    原图常有 7MB 以上，base64 之后接近 10MB，
     *                    大多数网关会直接拒掉，而那个失败看起来像「模型不可用」
     * @param contentType 如 {@code image/jpeg}；空则按 jpeg 处理
     * @return null = 认不出来 / 模型不可达
     */
    Cert recognize(byte[] imageBytes, String contentType);

    /**
     * 认出来的字段。**认不出的一律 null，不猜** ——
     * 猜出来的值会被商家当成「系统读到的」直接提交，比空着危险得多。
     *
     * @param docType    {@code BUSINESS_LICENSE} 营业执照 / {@code ID_CARD} 身份证 /
     *                   {@code UNKNOWN} 认不出是什么证
     * @param side       身份证专用：{@code FRONT} 人像面 / {@code BACK} 国徽面。其余证件为 null
     * @param name       执照上的名称，或身份证上的姓名
     * @param code       统一社会信用代码（执照）或身份证号。
     *                   <b>回给上传者本人是必要的</b> —— 他正要核对这一栏对不对；
     *                   但**不要写进日志、不要回给第三方**，那两处一律脱敏
     * @param legalForm  执照类型：个体工商户 / 有限责任公司 ……
     * @param person     法定代表人 / 经营者（执照）
     * @param address    住所、经营场所或住址
     * @param issuedAt   成立日期（执照）。{@code YYYY-MM-DD}
     * @param validTo    有效期止。{@code YYYY-MM-DD}，或字面量 {@code 长期}
     * @param confidence 模型自评，0–1。**只用来决定「预填还是仅提示」**，不作为放行依据
     */
    record Cert(String docType, String side, String name, String code, String legalForm,
                String person, String address, String issuedAt, String validTo, double confidence) {

        public static final String BUSINESS_LICENSE = "BUSINESS_LICENSE";
        public static final String ID_CARD = "ID_CARD";
        public static final String UNKNOWN = "UNKNOWN";
    }
}
