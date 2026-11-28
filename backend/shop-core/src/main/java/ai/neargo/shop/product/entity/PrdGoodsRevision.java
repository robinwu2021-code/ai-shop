package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品提交历史：一次保存一行快照（TDD-商品编辑页-录入落点与发布历史 §5.1）。
 *
 * <p><b>草稿也在这张表里</b>（门店交易库并表，批次 2-f）：原 {@code prd_goods_draft} 只存当前一份、
 * 发布就删行；现在当前那份是状态为 {@link #EDITING} / {@link #SUBMITTED} 的那一行，发布改为
 * {@link #ONLINE}、放弃改为 {@link #DISCARDED}，驳回落 {@link #REJECTED} 并复制一行新草稿 ——
 * 「现在待发布的是什么」与「发过什么」同一处可查。这张表是「发过什么」：发布不留痕，
 * 此前发完只剩线上那一份，谁在什么时候改了哪几项、上一版长什么样、为什么被驳回，
 * 一概无从回答。发布冲突时能说出「线上在你保存之后变过」，却说不出**是谁改的**。
 *
 * <p>⚠️ 版本号字段叫 {@code revisionNo} 不叫 {@code version}：{@code version} 是
 * {@link BaseEntity} 的 {@code @Version} 乐观锁列，同名会被 MyBatis-Plus 接走、
 * 每次 UPDATE 自增 —— 那就不是版本号了。
 *
 * <p>没有 savedBy / savedAt：{@code createdBy} / {@code createdAt} 就是它们。
 * {@code publishedBy} / {@code publishedAt} 另立 —— 保存与发布是两个时刻，
 * 而且常常是两个人。
 */
@Getter
@Setter
@TableName("prd_goods_revision")
public class PrdGoodsRevision extends BaseEntity {

    /**
     * 未发布。还没上架的商品直接改商品行，同时记一行这个状态（它不是草稿，见 {@link #EDITING}）。
     */
    public static final String DRAFT = "DRAFT";
    /**
     * 在售商品的草稿，编辑中（原 {@code prd_goods_draft} 的 EDITING，批次 2-f）。
     * <b>一件商品至多一行草稿</b>（{@link #EDITING} 或 {@link #SUBMITTED}），由锁商品行保证；
     * 「有未发布修改」的判据就是有没有这一行。线上照卖 {@code prd_goods}。
     */
    public static final String EDITING = "EDITING";
    /** 草稿已提交待审。线上旧版<b>继续卖</b> —— 这正是双版本的意义 */
    public static final String SUBMITTED = "SUBMITTED";
    /** 商家放弃了这份草稿。留在历史里，不再是草稿 */
    public static final String DISCARDED = "DISCARDED";
    /** 草稿的两种状态：编辑中、待审 */
    public static final java.util.List<String> DRAFT_STATES = java.util.List.of(EDITING, SUBMITTED);
    /** 线上在售的那一版。**不一定是最新那一版** —— 这是最容易看错的一点 */
    public static final String ONLINE = "ONLINE";
    /** 曾经上过线，已被后来的版本替换 */
    public static final String SUPERSEDED = "SUPERSEDED";
    /** 审核驳回。{@code rejectReason} 一起落 */
    public static final String REJECTED = "REJECTED";

    /** 手填 */
    public static final String SRC_MANUAL = "MANUAL";
    /** 快速录入（粘贴文字） */
    public static final String SRC_QUICK_TEXT = "QUICK_TEXT";
    /** 压缩包导入 */
    public static final String SRC_ZIP = "ZIP";
    /** 图片识别 */
    public static final String SRC_IMAGE = "IMAGE";

    private String goodsNo;
    private String entityNo;
    /** 同一商品内自增。见类注释：故意不叫 version */
    private Integer revisionNo;
    /** 这一版基于哪一版（上一个 ONLINE 的 revisionNo）。首版为 null */
    private Integer baseRevision;
    /** 整份 SaveCommand 的 JSON 快照。与草稿表同形，非契约 */
    private String payload;
    /**
     * 草稿基于哪一版线上（{@code prd_goods.version}，乐观锁列），只在草稿行上有值。
     * 发布时对不上 = 有人中途改过线上，拒。<b>不用 updated_at</b>：秒级精度，同秒改动检测不到。
     */
    private Long baseGoodsVersion;
    /**
     * 「改了哪几项」的字段标签，顿号连接。**不存结构化 diff** ——
     * diff 的两端是两份 payload，随时能重算；而标签是给人看的摘要，
     * 重算要依赖当时的 i18n 与类目文案，那些会变。
     */
    private String changeSummary;
    /** 录入方式。识别与历史的接缝：三个月后问「这批参数哪来的」，答案在这一列 */
    private String entrySource;
    private String status;
    private String rejectReason;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
}
