package ai.neargo.shop.media;

import java.util.List;

/**
 * 「我这个模块把图片地址放在哪些列里」。
 *
 * <p>做成<b>声明</b>而不是「各域自己写一段遍历代码」，是因为这些列全都长一个样：
 * 一个地址、一个 JSON 数组、或者一段嵌着地址的文本。各写一遍的必然结局是漂移，
 * 而这里漂移的代价是<b>删掉在用的图</b>。声明由统一的扫描器执行，只有一份实现。
 *
 * <p>与 {@code DataScopeRegistrar} 同一个套路：<b>登记是建表的一部分，不是可选项。</b>
 * 差别在于数据域漏登记是「静默放行越权数据」，这里漏登记是
 * 「那一列引用的图全被判成孤儿」—— 后者更响，但也更晚才被发现。
 * 所以配了一条 {@code MediaRefCoverageTest}：schema 里新出现一个图片列而没人声明，构建就失败。
 *
 * <p><b>宁可多声明，不要少声明。</b> 多声明一列的代价是每晚多跑一条 SQL；
 * 少声明一列的代价是不可逆的误删。两边不对称，所以拿不准的时候声明它。
 */
public interface MediaRefSource {

    List<MediaRefColumn> columns();
}
