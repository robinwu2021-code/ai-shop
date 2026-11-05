package ai.neargo.inventory.api.coordinated;

/**
 * 协同库存操作：给「不能把未知付款当作已释放」的外部协调方（商城硬件 SKU）用的持久操作协议。
 *
 * <p>语义（与宿主契约 {@code CoordinatedInventoryService} 一一对应，不得改变）：
 *
 * <ul>
 *   <li><b>按 operationNo 幂等</b>：同一 {@link Reference} 只认第一个预留操作号；同号同内容重放返回原证据，
 *       同号异内容报 CONFLICT。发货操作号同理。
 *   <li><b>release 是栅栏</b>：对一个从未预留过的 reference 释放也会落一条持久记录，之后迟到的 reserve
 *       只会拿到 RELEASED，不会再占货。
 *   <li><b>NOT_FOUND 只表示「没有这个操作」</b>，调用方不能把它当作已释放 —— 释放必须显式调用 release。
 * </ul>
 *
 * <p>失败只抛 {@link ai.neargo.inventory.api.InventoryException}。
 */
public interface CoordinatedOperations {

    /** 协调方自己的单号；{@code client} ≤ 32，{@code externalRef} ≤ 64。 */
    record Reference(String client, String externalRef) {}

    /** 首次预留时冻结的库存绑定；之后的发货与释放都按它，不随映射变更漂移。 */
    record Binding(
            String ownerId,
            String itemId,
            String locationId,
            String mappingVersion,
            String requestReference) {}

    record Reserve(Reference reference, String operationNo, Binding binding, int quantity) {}

    record Shipment(
            Reference reference,
            String operationNo,
            int quantity,
            String carrier,
            String trackingNo,
            String operatorNo) {}

    enum Status {
        RESERVED,
        SHIPPED,
        RELEASED,
        NOT_FOUND
    }

    /** operationQuantity 只属于 operationNo 这一个操作；held / issued 是整条预留的汇总诊断。 */
    record Evidence(
            Status status,
            String operationNo,
            int operationQuantity,
            int held,
            int issued,
            Binding binding,
            String documentNo) {}

    Evidence reserve(Reserve command);

    Evidence query(Reference reference, String operationNo);

    Evidence release(Reference reference, String originalReserveOperationNo);

    Evidence ship(Shipment command);
}
