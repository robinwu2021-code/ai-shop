package ai.neargo.shop.common;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务键生成：{@code 前缀 + yyyyMMddHHmmss + 4 位序 + 3 位随机}。
 *
 * <p>为什么不用 UUID：业务键会被人念（客服问「您的订单号是」）、会被打印在取货码小票上、
 * 会按时间排序做运营查询。UUID 三样都不占。
 *
 * <p>为什么带随机位：单机自增序在多实例部署下会撞号；随机位把碰撞概率压到可忽略，
 * 真撞了也有唯一索引兜底（业务键在库里一律建 UNIQUE）。
 */
public final class BizKey {

    /** 前缀是语义的一部分：看到 {@code SUB} 就知道这是子订单，不用去查表。 */
    public static final String ORDER = "SO";
    public static final String SUB_ORDER = "SUB";
    public static final String AFTER_SALE = "AS";
    public static final String USER = "U";
    public static final String ADDRESS = "AD";
    public static final String TICKET = "TK";
    public static final String MESSAGE = "MSG";
    public static final String MERCHANT = "M";
    public static final String MERCHANT_APPLY = "MA";
    public static final String STAFF = "ST";
    public static final String PICKUP_POINT = "PP";
    public static final String GOODS = "G";
    public static final String SKU = "SK";
    public static final String GROUP_BUY = "GB";
    public static final String GROUP_REQUEST = "GR";
    public static final String QUOTE = "Q";
    public static final String COUPON = "CP";
    public static final String REVIEW = "RV";
    public static final String APPEAL = "AP";
    public static final String SETTLE_BILL = "STL";
    public static final String EVENT = "EVT";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BizKey() {
    }

    public static String next(String prefix) {
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        int rand = RANDOM.nextInt(1000);
        return "%s%s%04d%03d".formatted(prefix, LocalDateTime.now().format(FMT), seq, rand);
    }
}
