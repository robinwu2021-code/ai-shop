package ai.neargo.shop.common;

/**
 * 「景滑村委会」（官方机构名）与「景滑」（商家从地图建档时随手起的名）是同一个地方 ——
 * 判断「已经有了没」不能按原字符串比，必须先去掉通名后缀再比。
 *
 * <p>这份归一化被两处用：{@code EstateCacheServiceImpl} 去重同一片抓回来的小区，
 * {@code BizRegionController} 判断官方村名录里的一条是不是已经开通过（避免同一个地方
 * 在搜索结果里出现两次：一条走「已开通」直接勾，一条走官方名录「点了要建档」）。
 * 两处各写一套的代价就是真出过——搜「景滑村」出两条，正是这个偏差。
 *
 * <p><b>为什么住在 common 而不是 platform</b>：它是一段**纯字符串归一化**，
 * 不碰任何域的数据、不依赖任何域的类型。放在 platform 下时，community 域调它
 * 就成了跨域依赖（`ArchitectureTest.svcModulesMustNotDependOnEachOther` 一直红着报它），
 * 而那条规则要拦的是「域之间互相知道对方的业务」—— 这不是。
 * 挪到 common 之后两边都合规，且 common 反向不依赖任何域这条也照样成立。
 */
public final class PlaceNames {

    private PlaceNames() {
    }

    public static String norm(String s) {
        if (s == null) {
            return "";
        }
        /*
         * **「村委会」必须单独列出**：正则的 `(...)+ $` 只会把结尾拆成几个「已知词」
         * 拼起来匹配，而「村委会」不是「村」+「委会」的拼接（「委会」不在词表里），
         * 所以漏了它，「景滑村委会」原样穿过、跟「景滑」比对不上 —— 真机上搜「景滑村」
         * 出两条，根子就在这里；补上之后要连同这一条一起测（PlaceNamesTest）。
         */
        return s.replaceAll("[（(].*?[）)]", "")
                .replaceAll("(小区|花园|家园|新村|苑|园|村委会|村|社区|居委会|村民委员会|居民委员会)+$", "")
                .trim();
    }
}
