package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店的预约时段与名额。
 *
 * <p><b>归属是门店不是商品</b>：能同时上几单取决于这家店有几个师傅，
 * 与卖的是保洁还是维修无关。挂到商品上的话，两个商品各配 3 个名额，
 * 同一个师傅会被这两条各约 3 次 —— 而系统里没有任何地方看得出来。
 *
 * <p>⚠️ <b>{@link #booked} 绝不能用「先查再改」维护。</b> 占位必须是一条
 * 带条件的 UPDATE，靠影响行数判断成败 —— 与库存锁定
 * （{@code ProductMappers.SkuMapper.lockStock}）是同一套手法，同一个理由：
 * 两个买家同时抢最后一个名额时，先查再改必然两个都成功。
 */
@Getter
@Setter
@TableName("mch_appointment_slot")
public class MchAppointmentSlot extends BaseEntity {

    /** 可约 */
    public static final String OPEN = "OPEN";
    /**
     * 停约。<b>不删行</b> —— 已经约进来的单还指着这一行，
     * 删掉的话取消订单时不知道该把名额还给谁，商家也查不到那天到底接了几单。
     */
    public static final String CLOSED = "CLOSED";

    private String slotNo;
    private String entityNo;
    private String storeNo;

    private Long startAt;
    private Long endAt;

    /** 这个时段能接几单。 */
    private Integer capacity;

    /**
     * 已占用。<b>与 capacity 分两列，不存「剩余」一个数</b> ——
     * 剩余是派生的，而「原本开了几个」在排期复盘时要用：
     * 只存剩余的话，一个满掉的时段和一个从没开过的时段长得一模一样。
     */
    private Integer booked;

    private String status;
}
