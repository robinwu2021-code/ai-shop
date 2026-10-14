package ai.neargo.shop.spi.reach;

/**
 * 「把请求里零散的『在哪儿』推成一个 {@link ConsumerProfile}」—— 给 <b>product 域</b>用的出口（ADR-034）。
 *
 * <p>为什么要有这个 Port：画像的推导要读聚落（带出区划码、父聚落、坐标兜底）、要做坐标反解，
 * 判据全在 community 域。{@code GoodsVisibility} 一开始<b>直接注入了
 * {@code community.service.ConsumerProfileResolver}</b>，两个域就此长在一起 ——
 * 而那种依赖编译得过、跑得通、不报错，只有 ArchitectureTest 那条规则能看见。
 *
 * <p>能力只开这一条（而不是把 Resolver 整个搬过来）：Port 是跨域契约，
 * product 域要的只是「给我一个画像」，不需要知道聚落表长什么样。
 */
public interface ConsumerProfilePort {

    /**
     * 推成画像。四个入参都可空，<b>确知即记、不臆造</b>：
     * 给了聚落号就从它带出区划码/父聚落/坐标兜底；没有区划码但有坐标就反解到区县；
     * 什么都没给就是一个空画像（判定侧据此不按地址筛）。
     */
    ConsumerProfile resolve(String communityNo, String regionCode, Integer latE6, Integer lngE6);
}
