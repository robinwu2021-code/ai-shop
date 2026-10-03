package ai.neargo.shop.inventory.service;

import java.util.List;

/**
 * 调拨：发出 → 收到两步，中间**停在 TRANSIT 库位**。
 *
 * <p>有在途这一段，{@code Σ on_hand} 才任意时刻守恒 —— 包括货在路上的那几天。
 * 不守恒的账发现不了错误：差多少都能解释成「在路上」。
 *
 * <p><b>一定生成两张单，哪怕骑车十分钟就送到。</b>一期允许发出即收到两步连着走，
 * 但两张单都要落 —— 省掉一张的话，将来要在途就得改历史数据。
 */
public interface TransferService {

    String create(String ownerId, String fromLocationId, String toLocationId,
                  List<Line> lines, String operator);

    /** 发出：生成出库单，货从来源库位移到 TRANSIT。 */
/**
     * 发货。
     *
     * @param carrierNo   承运方编号（主库 {@code ful_carrier}）；可空 —— 自己送就没有
     * @param carrierName 发货当时的名字快照。<b>由调用方从承运方列表里带下来</b> ——
     *                    进销存读不了主库，让它去查等于把跨库耦合塞进服务层
     * @param trackingNo  运单号；可空
     */
    void ship(String ownerId, String transferNo, String carrierNo, String carrierName,
              String trackingNo, String operator);

    /** 收到：生成入库单，货从 TRANSIT 移到目标库位。 */
    void receive(String ownerId, String transferNo, String operator);

    /**
     * 作废一张<b>还没发出</b>的调拨单。
     *
     * <p><b>只放行 DRAFT。</b>草稿只是「打算搬哪些货」，库存一个数都没动过，
     * 作废掉那张出库草稿即可。已 SHIPPED 的货正停在 TRANSIT 库位上 ——
     * 那不是「作废」能表达的事：货是真的离开了来源库位，要把它弄回去是**退回**，
     * 得再走一遍成对的一出一入。把两件事塞进同一个动作，商家点下去之后
     * 账上会凭空少一批货。RECEIVED 同理，那时该开一张反向调拨。
     *
     * <p>幂等：已经作废的再点一次不报错 —— 弱网下重复提交是常事。
     */
    void cancel(String ownerId, String transferNo, String operator);

    /**
     * 读回一张调拨单。
     *
     * <p>行取自**发出那张出库单** —— 草稿态还没有行，界面上要把这一点说清楚，
     * 否则一张还没发出的调拨单看起来像「空单」。
     */
    ai.neargo.shop.inventory.dto.InventoryVOs.TransferVO detail(String ownerId, String transferNo);

    record Line(String itemId, int qty) {
    }
}
