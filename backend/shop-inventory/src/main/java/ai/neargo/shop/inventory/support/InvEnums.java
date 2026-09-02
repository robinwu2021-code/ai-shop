package ai.neargo.shop.inventory.support;

/**
 * 本域的取值域。**字符串常量而不是 Java 枚举**，与仓库里 {@code OrdAfterSale.REFUNDED}
 * 这类写法一致：库里存的就是这些串，中间再隔一层枚举，反序列化失败时报的是
 * 「没有这个枚举常量」，而真正的问题是**库里出现了没人认识的值**。
 */
public final class InvEnums {

    private InvEnums() {
    }

    /** 库位类型。门店、仓、在途、虚拟（报废区/样品/借出）都是库位。 */
    public static final class LocationKind {
        public static final String STORE = "STORE";
        public static final String WAREHOUSE = "WAREHOUSE";
        /** 调拨途中的货停在这里 —— **有它，Σ on_hand 才任意时刻守恒**。 */
        public static final String TRANSIT = "TRANSIT";
        public static final String VIRTUAL = "VIRTUAL";

        private LocationKind() {
        }
    }

    /** 单据方向。流水只认这两个，业务语义在单据的 sourceType / purpose 上。 */
    public static final class DocKind {
        public static final String IN = "IN";
        public static final String OUT = "OUT";

        private DocKind() {
        }
    }

    /** 单据状态。**只有 POSTED 改余额**，前面加多少中间态都不影响这一条。 */
    public static final class DocStatus {
        public static final String DRAFT = "DRAFT";
        public static final String POSTED = "POSTED";
        public static final String VOIDED = "VOIDED";

        private DocStatus() {
        }
    }

    /** 入库来源。 */
    public static final class InboundSource {
        public static final String PURCHASE = "PURCHASE";
        /** 退货入库。**只有退货类售后才开**，仅退款不开。 */
        public static final String RETURN = "RETURN";
        public static final String TRANSFER_IN = "TRANSFER_IN";
        public static final String COUNT_GAIN = "COUNT_GAIN";
        public static final String INIT = "INIT";
        public static final String OTHER = "OTHER";

        private InboundSource() {
        }
    }

    /** 出库去向。 */
    public static final class OutboundPurpose {
        /** 销售出库。**只能由预留 commit 产生**，不接受手工创建。 */
        public static final String SALE = "SALE";
        public static final String TRANSFER_OUT = "TRANSFER_OUT";
        public static final String SCRAP = "SCRAP";
        public static final String COUNT_LOSS = "COUNT_LOSS";
        public static final String INTERNAL = "INTERNAL";
        /**
         * 退回供应商。<b>去向指向 {@code inv_supplier}</b> —— 供应商建了档，退货才有对象可指，
         * 而「这个月退给老周多少货」是应付账款对账的一半。
         */
        public static final String RETURN_SUPPLIER = "RETURN_SUPPLIER";
        public static final String OTHER = "OTHER";

        private OutboundPurpose() {
        }
    }

    /**
     * 出库去向的对象类型。<b>空表示没有去向</b> —— 报损就是没有去向的那一种，
     * 不要为它造一个 {@code NONE}：那会让「没去向」与「去向是无」变成两个值。
     */
    public static final class TargetType {
        public static final String SUPPLIER = "SUPPLIER";
        public static final String STORE = "STORE";

        private TargetType() {
        }
    }

    /** 差异原因。**枚举不是自由文本** —— 自由文本汇总不出「这个月报损了多少」。 */
    public static final class Reason {
        public static final String CHECK = "CHECK";
        public static final String BROKEN = "BROKEN";
        public static final String EXPIRED = "EXPIRED";
        public static final String GIFT = "GIFT";
        public static final String OTHER = "OTHER";

        private Reason() {
        }
    }

    /** 预留状态。 */
    public static final class ReservationStatus {
        public static final String HELD = "HELD";
        public static final String COMMITTED = "COMMITTED";
        public static final String RELEASED = "RELEASED";
        public static final String EXPIRED = "EXPIRED";

        private ReservationStatus() {
        }
    }

    /** 调拨状态。 */
    public static final class TransferStatus {
        public static final String DRAFT = "DRAFT";
        public static final String SHIPPED = "SHIPPED";
        public static final String RECEIVED = "RECEIVED";
        public static final String VOIDED = "VOIDED";

        private TransferStatus() {
        }
    }

    /** 主数据状态。软删一律走它 —— 全库没有 deleted 列。 */
    public static final class MasterStatus {
        public static final String ACTIVE = "ACTIVE";
        public static final String ARCHIVED = "ARCHIVED";
        public static final String DISABLED = "DISABLED";

        private MasterStatus() {
        }
    }

    /** 物料主数据来源。**两种交付形态的唯一分叉点**。 */
    public static final class DataSource {
        public static final String OWN = "OWN";
        public static final String SYNCED = "SYNCED";

        private DataSource() {
        }
    }

    /** 外部引用的来源系统。 */
    public static final class RefSystem {
        public static final String AISHOP = "AISHOP";
        public static final String ERP = "ERP";
        public static final String BARCODE = "BARCODE";
        public static final String POS = "POS";

        private RefSystem() {
        }
    }

    /** 成本口径。**不做移动加权** —— 漏录一次之后所有历史毛利全错且不报警。 */
    public static final class CostMethod {
        public static final String LATEST = "LATEST";
        public static final String MANUAL = "MANUAL";

        private CostMethod() {
        }
    }

    /** 领域事件。 */
    public static final class EventType {
        public static final String DOCUMENT_POSTED = "DocumentPosted";
        public static final String BALANCE_CHANGED = "StockBalanceChanged";
        public static final String RESERVATION_EXPIRED = "ReservationExpired";
        public static final String LOW_STOCK = "LowStockDetected";

        private EventType() {
        }
    }
}
