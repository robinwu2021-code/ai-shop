package ai.neargo.shop.media;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片资产记账。<b>「谁占了多少、哪些没人用了」的唯一依据 —— 不扫磁盘。</b>
 *
 * <p>在这张表出现之前，上传只把字节写进磁盘，库里一行都没有：
 * 门店占用要去服务器 {@code du}，而「哪些图被替换掉了」根本查不出来。
 *
 * <p><b>不删行，只改 {@link #status} 到 {@link #PURGED}</b> —— 删除不可逆，
 * 「什么时候删了什么」必须永远查得到。与 {@code SysOutbox} 同属基础设施表，
 * 因此同样不带 version / deleted / tenant_no。<b>尤其是 deleted</b>：
 * MyBatis-Plus 全局配了逻辑删除，这张表若带那一列，
 * 「保留已删记录以备审计」当场失效 —— 查出来的行会被自动过滤掉。
 *
 * <p>住在 {@code shop-base} 而不是某个业务域：上传写它、扫描改它、运营端读它，
 * 三方分属不同模块，和 Outbox、幂等表是同一类横切基础设施。
 */
@Data
@TableName("sys_media_asset")
public class SysMediaAsset {

    /** 刚写行、字节还没落盘。崩在这一步只留下可对账的行，不会产生查不出来的孤儿。 */
    public static final String PENDING = "PENDING";
    /** 在用，算进门店空间。 */
    public static final String ACTIVE = "ACTIVE";
    /**
     * 扫描判定无人引用，进待回收清单。
     *
     * <p><b>它不会自己往下走</b> —— 本期不做任何自动删除，
     * 删除一律要运营在页面上勾选、强确认、发起任务。
     * 下次扫描若发现它又被引用，会救回 {@link #ACTIVE}。
     */
    public static final String RECLAIMABLE = "RECLAIMABLE";
    /** 文件已删，行永久保留。 */
    public static final String PURGED = "PURGED";

    /** 商品图，公开读。 */
    public static final String GOODS = "GOODS";
    /** 证件影像（营业执照等），<b>私有读</b>，只能走签名 URL。 */
    public static final String QUAL = "QUAL";
    /** 售后凭证，私有读。 */
    public static final String AFTERSALE = "AFTERSALE";

    /**
     * {@link #storeNo} 的哨兵值：<b>这份资产属于经营主体，不属于任何一家门店</b>。
     *
     * <p>证件（{@link #QUAL}）就是这一类 —— 营业执照属于主体，不属于「文三路店」。
     * 这不是为了绕开「取不到门店」而设的妥协值，是把本来就存在的两种归属区分开：
     * 商品图是门店级的，证件是主体级的。
     *
     * <p>顺带解决一个真问题：进件阶段商家还没建店，
     * 若照搬 {@code requireStoreNo()} 会直接 403 —— 传不了证件也就进不了件。
     *
     * <p>下划线开头，与真实门店号（{@code S0003} 形态）不可能撞。
     * 运营端把这一档渲染成「主体级」，不挂在任何门店下。
     */
    public static final String ENTITY_SCOPE = "_ENTITY";

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 相对路径，形如 {@code E0001/S0003/goods/202608/9f2c….jpg}。
     * <b>这串字逐字就是将来的 COS object key</b>，切对象存储时不需要任何映射。
     */
    private String assetKey;

    private String entityNo;

    /**
     * 上传时的当前门店 —— 记的是<b>归属</b>（谁传的算谁的），不是展示关系。
     *
     * <p>商品是主体级的（{@code prd_goods.entity_no}），通过 {@code store_goods}
     * 上架到多家店，所以一张图可能被多家店展示。按展示算会重复计数、加起来大于磁盘实际占用；
     * 按归属算则各店之和正好等于真实字节数 —— 一个能对得上账的数才有意义。
     */
    private String storeNo;

    /** {@link #GOODS} / {@link #QUAL} / {@link #AFTERSALE}。决定公开读还是签名读。 */
    private String bizType;

    private Long bytes;

    /** 上传时顺手读出来。运营端要显示尺寸，事后再读要把每个文件都打开一遍。 */
    private Integer width;
    private Integer height;

    private String contentType;

    /** {@link #PENDING} / {@link #ACTIVE} / {@link #RECLAIMABLE} / {@link #PURGED} */
    private String status;

    /**
     * 最后一次被扫描到「仍在引用」的时刻；{@code null} = 从未被引用。
     *
     * <p>与 {@link #lastRefDesc} 一起构成运营端那列<b>「可回收理由」</b>，
     * 是扫描时落下的真实数据而不是事后推断：扫描遍历引用源时刷新这两列，
     * 于是一旦失去引用，它们就停在最后一次被引用的那一刻。
     */
    private LocalDateTime lastReferencedAt;

    /** 最后一个引用者的人话描述，如「商品 G0012 · 主图」。 */
    private String lastRefDesc;

    /**
     * 进待回收清单的时刻。<b>救回时必须置空</b> ——
     * 留着的话第二次进清单会用一个过期的起算点，「待了多少天」就是错的。
     */
    private LocalDateTime markedAt;

    /** 商家侧账号。运营要追问「这张图是谁传的」时找得到人。 */
    private String uploadedBy;

    private String purgeBatchNo;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime purgedAt;
}
