package ai.neargo.shop.trade.order.service.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.service.OrderReadStore;
import ai.neargo.shop.trade.order.contract.OrderListItemVO;
import ai.neargo.shop.trade.order.contract.OrderQuery;
import ai.neargo.shop.trade.order.contract.UnifiedOrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQueryServiceImplTest {

    /** 内存 fake：一张主单 O1(属主 U1)，两张子单，各一行 */
    static class FakeStore implements OrderReadStore {
        OrdOrder order = order();
        List<OrdSubOrder> subs = List.of(
                sub("O1-1", "M0001", OrdSubOrder.WAIT_FULFILL),
                sub("O1-2", "M0002", OrdSubOrder.FULFILLING));
        List<OrdItem> items = List.of(item("O1-1", "粮油", 2), item("O1-2", "鲜果", 1));

        static OrdOrder order() {
            OrdOrder o = new OrdOrder();
            o.setOrderNo("O1");
            o.setUserNo("U1");
            o.setStatus(OrdOrder.PAID);
            o.setPayAmount(15760L);
            return o;
        }

        static OrdSubOrder sub(String no, String entity, String status) {
            OrdSubOrder s = new OrdSubOrder();
            s.setSubOrderNo(no);
            s.setOrderNo("O1");
            s.setUserNo("U1");
            s.setEntityNo(entity);
            s.setStatus(status);
            s.setFulfillment("EXPRESS");
            return s;
        }

        static OrdItem item(String subNo, String title, int qty) {
            OrdItem i = new OrdItem();
            i.setSubOrderNo(subNo);
            i.setTitle(title);
            i.setQty(qty);
            i.setPrice(100L);
            return i;
        }

        public OrdOrder findOrder(String orderNo, String userNo) {
            return "O1".equals(orderNo) && "U1".equals(userNo) ? order : null;
        }

        public OrdSubOrder findSub(String subOrderNo, String userNo) {
            if (!"U1".equals(userNo)) {
                return null;
            }
            return subs.stream().filter(s -> s.getSubOrderNo().equals(subOrderNo)).findFirst().orElse(null);
        }

        public List<OrdSubOrder> subsOf(String orderNo) {
            return "O1".equals(orderNo) ? subs : List.of();
        }

        public List<OrdItem> itemsOf(String orderNo) {
            return "O1".equals(orderNo) ? items : List.of();
        }

        public PageData<OrdOrder> pageOrders(String userNo, OrderQuery query, long page, long size) {
            return "U1".equals(userNo) ? PageData.of(List.of(order), 1, page, size) : PageData.empty(page, size);
        }
    }

    private OrderQueryServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new OrderQueryServiceImpl(new FakeStore());
    }

    @Test
    @DisplayName("按主单号查 → 统一订单（2 包裹、聚合 PAID）")
    void detailByMainNo() {
        UnifiedOrderVO vo = svc.detail("O1", "U1");
        assertThat(vo).isNotNull();
        assertThat(vo.orderNo()).isEqualTo("O1");
        assertThat(vo.packageCount()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("兼容：按子单号也能查到同一个统一订单")
    void detailBySubNo() {
        UnifiedOrderVO vo = svc.detail("O1-2", "U1");
        assertThat(vo).isNotNull();
        assertThat(vo.orderNo()).isEqualTo("O1");
        assertThat(vo.packageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("越权：不是属主查不到（防 IDOR）")
    void detailBlockedForStranger() {
        assertThat(svc.detail("O1", "OTHER")).isNull();
        assertThat(svc.detail("O1-1", "OTHER")).isNull();
    }

    @Test
    @DisplayName("列表：一行一订单，packageCount=2")
    void listReturnsOrderGrain() {
        PageData<OrderListItemVO> p = svc.list("U1", new OrderQuery(null), 1, 10);
        assertThat(p.total()).isEqualTo(1);
        assertThat(p.records()).hasSize(1);
        assertThat(p.records().get(0).orderNo()).isEqualTo("O1");
        assertThat(p.records().get(0).packageCount()).isEqualTo(2);
        assertThat(p.records().get(0).itemCount()).isEqualTo(3);
    }
}
