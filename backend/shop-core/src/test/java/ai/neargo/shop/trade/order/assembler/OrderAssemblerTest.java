package ai.neargo.shop.trade.order.assembler;

import ai.neargo.shop.trade.order.contract.ContractOrderStatus;
import ai.neargo.shop.trade.order.contract.OrderListItemVO;
import ai.neargo.shop.trade.order.contract.UnifiedOrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAssemblerTest {

    private OrdOrder order(String no, String status) {
        OrdOrder o = new OrdOrder();
        o.setOrderNo(no);
        o.setStatus(status);
        o.setPayAmount(15760L);
        return o;
    }

    private OrdSubOrder sub(String no, String orderNo, String entityNo, String status, String fulfillment) {
        OrdSubOrder s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setOrderNo(orderNo);
        s.setEntityNo(entityNo);
        s.setEntityName(entityNo + "-名");
        s.setStatus(status);
        s.setFulfillment(fulfillment);
        return s;
    }

    private OrdItem item(String subNo, String sku, String title, int qty) {
        OrdItem i = new OrdItem();
        i.setSubOrderNo(subNo);
        i.setSkuNo(sku);
        i.setTitle(title);
        i.setQty(qty);
        i.setPrice(4980L);
        i.setAmount(4980L * qty);
        return i;
    }

    @Test
    @DisplayName("多子单 → 一个统一订单，packages 对应子单、packageId=P1/P2、聚合状态取最落后")
    void multiSubAssembled() {
        OrdOrder o = order("O1", OrdOrder.PAID);
        List<OrdSubOrder> subs = List.of(
                sub("O1-1", "O1", "M0001", OrdSubOrder.WAIT_FULFILL, "EXPRESS"),
                sub("O1-2", "O1", "M0002", OrdSubOrder.FULFILLING, "EXPRESS"));
        List<OrdItem> items = List.of(
                item("O1-1", "SK1", "粮油", 2),
                item("O1-2", "SK2", "鲜果", 1));

        UnifiedOrderVO vo = OrderAssembler.toUnified(o, subs, items);

        assertThat(vo.orderNo()).isEqualTo("O1");
        assertThat(vo.packageCount()).isEqualTo(2);
        assertThat(vo.packages()).extracting("packageId").containsExactly("P1", "P2");
        // 聚合：WAIT_FULFILL(→PAID) 最落后 → PAID
        assertThat(vo.status()).isEqualTo(ContractOrderStatus.PAID);
        // 每个包裹的 contract 状态
        assertThat(vo.packages().get(0).status()).isEqualTo(ContractOrderStatus.PAID);
        assertThat(vo.packages().get(1).status()).isEqualTo(ContractOrderStatus.FULFILLING);
        // 不暴露"子单号"当订单号，但内部字段保留
        assertThat(vo.packages().get(0).subOrderNo()).isEqualTo("O1-1");
        // 行按子单分组
        assertThat(vo.packages().get(0).items()).hasSize(1);
        assertThat(vo.packages().get(0).items().get(0).title()).isEqualTo("粮油");
    }

    @Test
    @DisplayName("单商家单 → packages=1")
    void singleMerchant() {
        OrdOrder o = order("O2", OrdOrder.PAID);
        List<OrdSubOrder> subs = List.of(sub("O2-1", "O2", "M0001", OrdSubOrder.FULFILLING, "EXPRESS"));
        List<OrdItem> items = List.of(item("O2-1", "SK1", "粮油", 1));

        UnifiedOrderVO vo = OrderAssembler.toUnified(o, subs, items);
        assertThat(vo.packageCount()).isEqualTo(1);
        assertThat(vo.status()).isEqualTo(ContractOrderStatus.FULFILLING);
    }

    @Test
    @DisplayName("超时关闭单：聚合对外 CANCELLED（不漏 CLOSED）")
    void closedOrderAggregatesCancelled() {
        OrdOrder o = order("O3", OrdOrder.CLOSED);
        List<OrdSubOrder> subs = List.of(sub("O3-1", "O3", "M0001", OrdSubOrder.CANCELLED, "EXPRESS"));
        UnifiedOrderVO vo = OrderAssembler.toUnified(o, subs, List.of());
        assertThat(vo.status()).isEqualTo(ContractOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("列表项：一行一订单，packageCount + 首商品 + 件数合计")
    void listItem() {
        OrdOrder o = order("O1", OrdOrder.PAID);
        List<OrdSubOrder> subs = List.of(
                sub("O1-1", "O1", "M0001", OrdSubOrder.WAIT_FULFILL, "EXPRESS"),
                sub("O1-2", "O1", "M0002", OrdSubOrder.FULFILLING, "EXPRESS"));
        List<OrdItem> items = List.of(item("O1-1", "SK1", "粮油", 2), item("O1-2", "SK2", "鲜果", 1));

        OrderListItemVO vo = OrderAssembler.toListItem(o, subs, items);
        assertThat(vo.orderNo()).isEqualTo("O1");
        assertThat(vo.packageCount()).isEqualTo(2);
        assertThat(vo.title()).isEqualTo("粮油");
        assertThat(vo.itemCount()).isEqualTo(3);
        assertThat(vo.status()).isEqualTo(ContractOrderStatus.PAID);
    }
}
