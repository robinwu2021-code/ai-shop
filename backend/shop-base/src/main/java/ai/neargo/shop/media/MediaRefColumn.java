package ai.neargo.shop.media;

/**
 * 「哪张表的哪一列里放着图片地址」的一条声明。
 *
 * <p>回收机制的判据是「还有没有人引用」，而这份清单就是「谁算引用」的全集 ——
 * <b>漏一条的后果不是少删几张，是那一列引用的图全被判成孤儿然后删掉</b>。
 * 所以它由 {@code MediaRefCoverageTest} 与 schema 对账，新加一个图片列不登记就构建失败。
 *
 * <p><b>刻意没有「这一列是单值还是 JSON 数组还是自由文本」这个字段。</b>
 * 初稿有，写扫描器时发现三者的处理完全一样 —— 都是从字符串里把形如
 * {@code /uploads/E0001/…/x.jpg} 的地址全抠出来。而留着它反而多一类静默故障：
 * 一列实际存 JSON 数组却被声明成单值，按声明解析就只能取到零个或一个引用，
 * <b>剩下那些图会被判成孤儿删掉</b>，且没有任何报错。
 * 去掉这个字段就去掉了整类「声明错了」的可能。
 *
 * @param table     表名。<b>只能是代码里的常量</b>，永远不经过请求参数 ——
 *                  它会被拼进 SQL（MyBatis 无法参数化表名），这一条是它安全的唯一理由，
 *                  与 {@code ArchiveMapper} 同一个约定
 * @param column    列名，约束同上
 * @param label     人话描述的模板，如「商品 · 主图」。运营端那列
 *                  「曾被『商品 G0012 · 主图』引用」就是拿它拼的
 * @param keyColumn 业务键列（如 {@code goods_no}），用来把 label 拼成具体的哪一条
 */
public record MediaRefColumn(String table, String column, String label, String keyColumn) {

    public static MediaRefColumn of(String table, String column, String label, String keyColumn) {
        return new MediaRefColumn(table, column, label, keyColumn);
    }
}
