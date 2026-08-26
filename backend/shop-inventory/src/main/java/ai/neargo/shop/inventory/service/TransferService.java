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
    void ship(String ownerId, String transferNo, String operator);

    /** 收到：生成入库单，货从 TRANSIT 移到目标库位。 */
    void receive(String ownerId, String transferNo, String operator);

    record Line(String itemId, int qty) {
    }
}
