package ai.neargo.shop.trade.order.transition;

/**
 * 有状态机的四个聚合，以及它们的状态落在哪张表的哪一列（转换门按这里 CAS）。
 */
public enum Aggregate {
    PAYMENT("ord_order", "order_no"),
    ORDER("ord_sub_order", "sub_order_no"),
    SHIPMENT("ord_shipment", "shipment_no"),
    AFTER_SALE("ord_after_sale", "after_sale_no");

    private final String table;
    private final String noColumn;

    Aggregate(String table, String noColumn) {
        this.table = table;
        this.noColumn = noColumn;
    }

    public String table() {
        return table;
    }

    public String noColumn() {
        return noColumn;
    }
}
