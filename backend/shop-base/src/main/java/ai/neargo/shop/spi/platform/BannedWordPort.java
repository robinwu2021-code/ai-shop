package ai.neargo.shop.spi.platform;

import java.util.Optional;

/**
 * 任意域 → platform：查一段文本里有没有平台禁售词。
 *
 * <p>与 {@link AuditLogPort} 同一类：<b>横切关注点</b>。词表归平台管，
 * 而要查的文本散在各个域（今天是商品标题，将来可能是店招公告、评价内容）。
 * 让每个域为了查一个词去依赖整个 platform 域，是把一件小事变成模块依赖。
 *
 * <p><b>返回命中的那个词，不是一个布尔。</b> 「你的标题里有违禁词」这句话
 * 对商家没有用 —— 他要改的是哪两个字。事后驳回的老路子之所以低效，
 * 一半原因就是驳回理由是人手写的，读完还是不知道改哪儿。
 */
public interface BannedWordPort {

    /**
     * 文本里第一个命中的禁售词；没有则 {@link Optional#empty()}。
     *
     * <p>只给第一个：列全部会让报错变成一段清单，而商家改完第一个还要再提交一次 ——
     * 逐个提示与一次列全在体验上差不多，而前者的实现不会随词表变大而变慢。
     */
    Optional<Hit> firstHit(String text);

    /**
     * @param word   命中的词
     * @param reason 为什么禁。<b>会原样给到商家</b>，所以它是一句人话不是一个代码
     */
    record Hit(String word, String reason) {
    }
}
