package ai.neargo.shop.inventory.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本域的业务键：{@code 前缀 + yyyyMMddHHmmss + 4 位序 + 3 位随机}。
 *
 * <p><b>为什么不用平台的 {@code BizKey}</b>：格式刻意保持一致（人念得出、按时间排得动），
 * 但**不复用那一个类** —— 独立交付时 {@code shop-base} 整个不在，
 * 而单据号是这个领域对外发出去的东西。为一个二十行的生成器保留跨模块依赖，
 * 换来的是「客户装不起来」。
 *
 * <p>撞号靠随机位压概率，真撞了有唯一索引兜底（业务键在库里一律 UNIQUE）。
 */
public final class InvKeys {

    /** 入库单 */
    public static final String INBOUND = "IN";
    /** 出库单 */
    public static final String OUTBOUND = "OUT";
    /** 盘点单 */
    public static final String COUNT = "CNT";
    /** 调拨单 */
    public static final String TRANSFER = "TRF";
    /** 预留 */
    public static final String RESERVATION = "RSV";
    /** 物料 */
    public static final String ITEM = "ITM";
    /** 库位 */
    public static final String LOCATION = "LOC";
    /** 业主 */
    public static final String OWNER = "OWN";
    /** 领域事件 */
    public static final String EVENT = "EVT";
    /** Open API 凭证 */
    public static final String CREDENTIAL = "CRD";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private InvKeys() {
    }

    public static String next(String prefix) {
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        int rnd = ThreadLocalRandom.current().nextInt(1000);
        return prefix + LocalDateTime.now().format(TS)
                + String.format("%04d", seq) + String.format("%03d", rnd);
    }

    /**
     * Open API 的 secret。
     *
     * <p><b>与业务键不是一回事，所以不走 {@link #next}</b>：业务键要人念得出、
     * 按时间排得动，而这三条恰恰是密钥不能有的性质 —— 带时间戳的密钥泄露了半截
     * 就能猜出另半截。这里要的只有一件事：<b>不可预测</b>。
     *
     * <p>用 {@code SecureRandom}。{@code ThreadLocalRandom}（上面那个键生成器用的）
     * 是可预测的伪随机 —— 拿它当密钥，看几个样本就能推出种子。
     */
    public static String secret() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf);
    }
}