package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品提交历史：一次保存一行快照（TDD-商品编辑页-录入落点与发布历史 §5.1）。
 *
 * <p>与 {@link PrdGoodsDraft} 的分工：草稿表**只存当前一份**（{@code goods_no} 唯一），
 * 发布就把行删掉 —— 那是「现在待发布的是什么」。这张表是「发过什么」：发布不留痕，
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

    /** 未发布。保存就落这个状态 */
    public static final String DRAFT = "DRAFT";
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
