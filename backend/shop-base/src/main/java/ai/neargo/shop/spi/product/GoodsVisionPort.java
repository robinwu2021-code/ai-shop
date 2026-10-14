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
     * <p><b>facts 是这个方法唯一的事实来源扩展</b>（TDD-商品描述带参数生成）。
     * 提示词 v3 的第一句是「你只知道商品名、卖点、类目这三项，别的一概不知道」——
     * 那是 v1/v2 两次编造（「散养土鸡蛋…蛋黄饱满」「明早截单，后天一早送到」）换来的。
     * 所以**不是放宽规则，是补事实**：商家自己在建品页填过的参数（产地、口感、储存条件…）
     * 是经过候选值核验的结构化数据，模型照抄它们不算编。
     *
     * @param imageUrl 商品主图，可为空（没图就只按文字写）
     * @param title    商品名。**必须有** —— 没有名字的话模型只能瞎编
     * @param subtitle 副标题，可为空
     * @param category 类目中文路径，如「食品生鲜 / 水果」，可为空
     * @param facts    商家已填的商品参数，可为空/null。**空时提示词与加这个参数之前逐字相同**。
     *                 复用 {@link ParamKV}（名 + 值）：那个记录本来表示「模型抽出来的一条参数」，
     *                 形状与这里要的逐字相同，没有理由再造一个近似类型。
     *                 **只传中文名与中文值**（「产地」「山西运城临猗」），`dimNo` 用不上 ——
     *                 与 {@code categoryPath()} 把类目号翻成中文名喂模型是同一条取舍：
     *                 模型认得「产地」，不认得 SD_ORIGIN_DETAIL。
     * @return null = 没生成出来 / 模型不可达
     */
    String describe(String imageUrl, String title, String subtitle, String category,
                    java.util.List<ParamKV> facts);

    /**
     * 按类目给定的候选值，替商家**挑**商品参数（TDD-C 端商品详情页·内容丰富度 §2.B）。
     *
     * <p><b>是挑不是写</b>：候选值由平台的类目模板给定，模型只能在里面选，选不出就不选。
     * 让它自由发挥的话会冒出「口感：入口即化」这种不在值集里的字符串，
     * 而参数区要的是可比、可筛的枚举 —— 自由文本那条路商家自己填就有。
     *
     * <p>取不到模型时返回空 Map，调用方照旧把空候选摆给商家自己点。
     *
     * @param candidates 维度名 → 该维度的候选值标签，例如 {@code {"储存条件": ["常温","冷藏 0~5℃"]}}
     * @return 维度名 → 选中的值标签。<b>只会是 candidates 里出现过的字符串</b>，
     *         没把握的维度直接缺席，不硬凑
     */
    default Map<String, String> suggestParams(String imageUrl, String title, String subtitle,
                                              String category, Map<String, java.util.List<String>> candidates) {
        return Map.of();
    }

    /**
     * 从一段商家随手写的**商品文字**里抽结构化信息（「文字也走 LLM」）。
     *
     * <p>与规则解析器（{@code GoodsTextRuleParser}）互补：规则稳定抽确定字段（价格/快递/省），
     * LLM 补**参数归类**（「单果140g+」→单果重量、「净重4.5斤」→净重）与省名识别。
     * 价格仍以规则为准（真金白银不交给概率）；冲突规则压 LLM。
     *
     * <p>失败/未启用一律返回 {@code null}，调用方退回纯规则。
     */
    default TextExtract extractText(String text) {
        return null;
    }

    /**
     * 按**品类的标准参数清单**抽（TDD-商品快速录入-品类感知与逐项确认 §7 第 2 条）。
     *
     * <p>同一个「140g」，苹果是单果重量、手机壳是重量、洗衣液是净含量 —— 落点取决于它是什么。
     * 不给清单的话模型只能自由发挥属性名（「净重」），而这个品类的标准参数叫「净含量」，
     * 两边对不上，识别出来的值就成了一条游离的自由参数：写进去了，却不是这个商品的那个参数。
     *
     * <p>默认实现退回不带清单的那个 —— 老实现与测试替身不用改。
     *
     * @param hints 本品类可落的标准参数；空 = 不知道品类，退回自由抽取
     */
    default TextExtract extractText(String text, java.util.List<ParamHint> hints) {
        return extractText(text);
    }

    /**
     * 一个标准参数：维度号 + 名称 + 值类型。给模型的「只能往这里落」清单里的一行。
     *
     * @param valueType ENUM / QUANT / TEXT；模型据此知道值该怎么写，可空
     */
    record ParamHint(String dimNo, String name, String valueType) {
    }

    /**
     * 文字抽取结果。{@code provinces} 是「不发货/限购」到的**省名**（调用方用
     * {@code Provinces.codeOfName} 映射成省级码）；{@code priceYuan} 可空（规则没抽到时才用）。
     */
    record TextExtract(String name, java.util.List<ParamKV> params, Double priceYuan,
                       java.util.List<String> fulfillment, String courier,
                       java.util.List<String> provinces, double confidence) {
    }

    /**
     * 一条抽出来的参数：属性名 + 值（如 单果重量 / 140g+），以及模型选中的**标准维度号**。
     *
     * @param dimNo 模型从清单里选的维度号；没给清单、或模型没选，为 null。
     *              **不可直接信** —— 调用方要核对它确实在清单里（模型会编）
     */
    record ParamKV(String name, String value, String dimNo) {
        /** 不带维度号的那种（没给清单时的旧形状）。保留它，既有调用点一行不改 */
        public ParamKV(String name, String value) {
            this(name, value, null);
        }
    }

    /**
     * 把压缩包的**文件结构**归到标准结构（TDD-商品压缩包导入 AC11）：主图 / 详情 / 文案 / 不导入。
     *
     * <p>只看路径与宽高，不看图片内容 —— 不用先上传，也快。目录叫「01-首图」「长图」「白底」都行，
     * 这正是规则（只认「主图/详情」）做不到的那一半。
     *
     * <p>失败/未启用返回 {@code null}；返回值**不可直接信**（模型会编路径、漏文件），
     * 调用方逐文件校验，不合格的退回规则。
     *
     * @param title    商品名，可空
     * @param category 类目名，可空
     * @param files    文件清单（已滤掉系统文件）
     * @param txtPreview 根目录 txt 的前若干字，可空 —— 帮模型判断它是不是商品文案
     */
    default java.util.List<ZipPick> mapZip(String title, String category,
                                           java.util.List<ZipFile> files, String txtPreview) {
        return null;
    }

    /** 压缩包里的一个文件。宽高读不到时为 null（txt 没有宽高） */
    record ZipFile(String path, Integer width, Integer height) {
    }

    /**
     * 一个文件的去向。
     *
     * @param target MAIN / DETAIL / TEXT / IGNORE
     * @param order  同一去向内的顺序，从 1 起；IGNORE 为 0
     * @param cover  是否适合当封面（只对 MAIN 有意义，调用方只取一张）
     */
    record ZipPick(String path, String target, int order, boolean cover) {
    }

    /**
     * 经营范围文字录入：一句话 → 结构化地点清单（TDD-经营范围文字录入 §7 大模型识别）。
     *
     * <p>放在这个端口上是因为它与商品文字抽取<b>同一个模型、同一套连接与降级</b>；
     * 名字里的 Goods 是历史原因。<b>模型只给名字，不给码</b> —— 码由调用方逐级在库里查。
     *
     * @return 没配模型、超时、返回体解析不了时为 null，调用方退回规则识别
     */
    default ScopeExtract extractScope(String text) {
        return null;
    }

    /**
     * @param unlimited 「全国 / 不限 / 除…以外的其他地区」
     * @param places    地点，按原话顺序
     * @param unclear   原话提到、模型拿不准是哪里的说法（原样）
     */
    record ScopeExtract(boolean unlimited, java.util.List<ScopePlace> places, java.util.List<String> unclear) {
    }

    /**
     * @param path  从省级开始的行政区划全称，逐级到原话说到的那一级；小区/楼栋原样接在最后
     * @param mode  INCLUDE / EXCLUDE
     * @param text  原话里对应的那一段
     * @param guess 原话没点名、按常识展开的（「江浙沪」「偏远地区」）—— 端上默认不勾
     */
    record ScopePlace(java.util.List<String> path, String mode, String text, boolean guess) {
    }
}
