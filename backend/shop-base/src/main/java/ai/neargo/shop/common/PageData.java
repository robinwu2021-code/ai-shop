package ai.neargo.shop.common;

import java.util.List;

/**
 * 分页包 {@code {records, total, page, size}}（与 c-app 契约一致，见 {@link ApiResult} 的说明）。
 *
 * <p>字段名刻意与 MyBatis-Plus {@code IPage} 的 {@code records} 对齐，
 * 这样从 {@code IPage} 转过来是一次直译，不需要在每个 Service 里手抄四个字段。
 *
 * <p><b>那个直译方法不在这里，在 {@code MybatisPages.of(IPage)}</b>
 * （{@code shop-store-mybatis}）。它原本是本记录的一个静态工厂 ——
 * <b>一个纯粹的分页契约，被一个静态方法钉在了 MyBatis 上</b>：
 * 想用 {@code PageData} 就得把 MyBatis 装进 classpath。
 * 全仓 32 处 {@code PageData.of(}，其中传 {@code IPage} 的只有个位数，
 * 而那一个方法让 job 与将来的 pay 连错误码都用不上。
 */
public record PageData<T>(List<T> records, long total, long page, long size) {

    public static <T> PageData<T> of(List<T> records, long total, long page, long size) {
        return new PageData<>(records, total, page, size);
    }

    /**
     * 把一份**已经全量取出**的列表包成分页壳。
     *
     * <p>为什么需要它：运营端所有列表页都按 {@code {records,total}} 渲染 ——
     * 后端返回裸数组的话，页面会把它当成「空页」：<b>接口 200、数据几十条、
     * 页面显示「暂无数据」</b>，而控制台一条错误都没有。这个坑本轮踩了十次，
     * 全都是「在浏览器里打开才发现」。
     *
     * <p>只适合本来就要全量算的列表（类目树、异常单视图、几十条量级的主数据）。
     * 真正的大表请走 SQL 分页 —— 这个方法会把整张表读进内存。
     */
    public static <T> PageData<T> ofAll(List<T> all, long page, long size) {
        long from = Math.max(0, (page - 1) * size);
        List<T> rows = from >= all.size() ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + size));
        return new PageData<>(rows, all.size(), page, size);
    }

    public static <T> PageData<T> empty(long page, long size) {
        return new PageData<>(List.of(), 0, page, size);
    }
}
