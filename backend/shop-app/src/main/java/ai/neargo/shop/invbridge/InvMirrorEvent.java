package ai.neargo.shop.invbridge;

import java.util.Set;

/**
 * 库存双写镜像事件的取值域 —— <b>唯一声明处</b>。
 *
 * <h2>此前它散在三处，而且 grep 搜不到</h2>
 *
 * <p>同一个取值域有过三种写法：
 * <ul>
 *   <li>生产方 {@code DualWriteStockPort.lock()}：{@code mirror("MIRROR_RESERVE", …)}
 *       —— 只有<b>半个</b>名字</li>
 *   <li>同类 {@code MirrorEvent.eventType()}：{@code return "INV_" + type} —— 拼出来的</li>
 *   <li>消费方 {@code InventoryMirrorConsumer}：{@code case "INV_MIRROR_RESERVE"}
 *       —— 整个名字</li>
 * </ul>
 *
 * <p>后果不只是「三处要一起改」。<b>{@code grep "INV_MIRROR_RESERVE"} 找不到生产方</b>
 * —— 因为那一半从来没有以完整形态出现过。「改名前先全局搜一遍」是这个仓库最常用的
 * 手势，而它在这里直接失效：搜出来只有消费方，看上去像是没人发这个事件。
 * 2026-09-06 盘点取值域时就是这么误判了一次，以为四个分支是死代码。
 *
 * <p>所以这里的常量<b>带全前缀</b>，生产方不再做任何拼接。少一次拼接换来的是
 * 「这个名字在代码里搜得到」。
 *
 * <h2>为什么前缀必须留着</h2>
 *
 * <p>平台的 {@code eventType} 是全局的：进销存的 {@code POSTED} 与订单的 {@code POSTED}
 * 撞在一起时消费方分不出是谁的。所以域前缀是契约的一部分，不是装饰。
 *
 * <h2>漏一个分支会怎样</h2>
 *
 * <p>消费方的 {@code supports()} 按前缀<b>开放认领</b>：任何 {@code INV_MIRROR_*}
 * 都归它。于是 {@link #ALL} 里加了成员却忘了加 {@code case} 时，事件会被认领、
 * 落进 {@code default}、只留一条 WARN —— 平台侧扣了预留而进销存侧没有，
 * 两本账当场分叉，而没有任何东西会失败。
 * {@code InvMirrorEventCoverageTest} 守的就是这一条。
 */
public final class InvMirrorEvent {

    /** 域前缀。消费方靠它认领，见类注释「为什么前缀必须留着」。 */
    public static final String PREFIX = "INV_MIRROR_";

    /** 平台锁库 → 进销存建预留。 */
    public static final String RESERVE = PREFIX + "RESERVE";
    /** 平台确认 → 进销存把预留转成出库。 */
    public static final String COMMIT = PREFIX + "COMMIT";
    /** 平台释放 → 进销存撤销预留。 */
    public static final String RELEASE = PREFIX + "RELEASE";
    /** 售后退回 → 进销存回补库存。 */
    public static final String RESTORE = PREFIX + "RESTORE";
    /**
     * 商家在商品页手改库存 → 进销存跟着改。
     *
     * <p><b>它没有 ref</b>（自然键是 skuNo + 目标值），所以消费方对它单独放行，
     * 不走「缺 ref 就丢弃」那一条。
     */
    public static final String ADJUST = PREFIX + "ADJUST";

    /**
     * 全部镜像事件。<b>消费方必须为每一个都有一个 {@code case}</b>。
     *
     * <p>这些常量是编译期常量（前缀与后缀都是字面量），所以能直接写在
     * {@code case} 标签上 —— 拼写不一致因此在编译期就没法发生。
     * 消不掉的是<b>遗漏</b>，那一档交给 {@code InvMirrorEventCoverageTest}。
     */
    public static final Set<String> ALL = Set.of(RESERVE, COMMIT, RELEASE, RESTORE, ADJUST);

    private InvMirrorEvent() {
    }
}
