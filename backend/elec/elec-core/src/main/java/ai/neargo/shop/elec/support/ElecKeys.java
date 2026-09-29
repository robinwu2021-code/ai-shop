package ai.neargo.shop.elec.support;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** 本域的业务编号。与 InvKeys 同一个形状：前缀 + 秒级时间 + 序号 + 随机。 */
public final class ElecKeys {

    public static final String PART = "EP";
    public static final String SUPPLIER = "ES";
    public static final String BATCH = "EB";
    public static final String STOCK = "EK";
    public static final String RFQ = "EQ";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 匿名代号的字母表：去掉 0/O/1/I 这类念出来会听错的 */
    private static final char[] MASK_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private ElecKeys() {
    }

    public static String next(String prefix) {
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        int rnd = ThreadLocalRandom.current().nextInt(1000);
        return prefix + LocalDateTime.now().format(TS) + String.format("%04d%03d", seq, rnd);
    }

    /** 供应商匿名代号 {@code S-XXXX}。撞了由唯一键兜底、调用方重试 */
    public static String maskCode() {
        StringBuilder sb = new StringBuilder("S-");
        for (int i = 0; i < 4; i++) {
            sb.append(MASK_ALPHABET[RND.nextInt(MASK_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
