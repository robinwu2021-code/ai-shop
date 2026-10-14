package ai.neargo.shop.fulfillment.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 快递运单记录（P-5.2.1）。
 *
 * <p><b>为什么不能只靠 {@code ord_sub_order.express_no}</b>：那是一个字符串列，
 * 换一次单号旧值就没了。而「换单号」恰恰是运营在这一页唯一能做的动作，
 * 也是事后对不上时的唯一线索 —— 覆盖掉等于把线索删了。
 *
 * <p>⚠️ <b>一期不对接快递鸟/菜鸟</b>（ADR-005 §5）。这张表做的是「存住 + 展示」：
 * 运单号回填、轨迹留痕。状态由订单状态推导，<b>平台不编造轨迹推进</b>。
 */
@Getter
@Setter
// V387 起表名改为 lgs_ 前缀（TDD-物流模块 批 1）。本类是过渡：批 2 起由 logistics 模块的实体取代
@TableName("lgs_waybill")
public class FulShipment extends BaseEntity {

    public static final String CREATED = "CREATED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String DELIVERED = "DELIVERED";

    /**
     * 疑难件。<b>不是终态</b> —— 快递可能「疑难」之后又派送成功。
     * 做成终态的话运营就得手工把单子拉回来，而那本该是承运商回传的事。
     *
     * <p>一期<b>没有产生它的路径</b>（不接回传），保留取值是为了接回传时不用改契约。
     */
    public static final String EXCEPTION = "EXCEPTION";

    /**
     * 平台侧主键，<b>不是快递单号</b>。
     * 分成两个键是因为换单号时运单记录必须还是同一条，否则轨迹会断在换单那一刻。
     */
    private String shipmentNo;

    /** 列名 V387 起是 biz_ref（运单不只属于子单：将来还有售后寄回）；Java 侧过渡期沿用旧名 */
    @TableField("biz_ref")
    private String subOrderNo;

    /** SF / JD / YTO。建单时取<b>当时优先级最高的启用运力</b>并快照。 */
    private String carrier;

    private String waybillNo;

    private String status;

    /**
     * 收件人姓名快照。<b>不现查 {@code usr_address}</b> —— 那张表可改可删，
     * 改了之后运营看到的收件人跟货上贴的不是一个人。
     */
    private String receiver;

    /** 收件地区（省 市）。超区判断看的就是它。 */
    private String region;

    /** 已备好载荷的展示渠道名（wx-plugin / self-map…）。空=还没备过 */
    private String displayChannel;

    /** 该渠道的载荷。微信插件存 waybill_token */
    private String displayToken;

    /** 最近一次备载荷失败的原因，给运营排查用 */
    private String displayFailReason;

    /** 上次向数据源查轨迹的时刻(ms)。缓存判据，见 TDD-物流轨迹多渠道 §2.8 */
    private Long traceQueriedAt;

    /** 上次备展示载荷的时刻(ms)，成功失败都记 */
    private Long displayPreparedAt;

    /**
     * 签收时间（毫秒）。**推进到 {@link #DELIVERED} 那一刻记下**，不事后从轨迹节点反推 ——
     * 节点会被后续查询追加、顺序也不保证，反推出来的时间可能早于发货，
     * 而微信确认收货提醒要求 {@code received_time} 晚于发货时间（否则 10060029）。
     */
    private Long signedAt;
}
