package ai.neargo.shop.media;

import org.springframework.stereotype.Component;

import java.util.List;

import static ai.neargo.shop.media.MediaRefColumn.of;

/**
 * shop-core 各域把图片地址放在哪些列里。
 *
 * <p><b>为什么一个模块只有一份而不是每个域各一份</b>：这里没有任何域的逻辑，
 * 只有一串表名列名。摊到七个域里的唯一效果是「想知道全集要翻七个文件」，
 * 而这份清单最需要的恰恰是能一眼扫完 —— 漏一行的代价是那一列引用的图全被判成孤儿。
 */
@Component
public class CoreMediaRefs implements MediaRefSource {

    @Override
    public List<MediaRefColumn> columns() {
        return List.of(
                // —— 商品 ——
                of("prd_goods", "cover", "商品 · 主图", "goods_no"),
                of("prd_goods", "images", "商品 · 图集", "goods_no"),

                /*
                 * 订单项的封面是**下单那一刻的快照**，指向的仍是商品图那张文件。
                 *
                 * **这一行漏了就是事故**：商家把商品图从 A 换成 B 之后，A 在 prd_goods 里
                 * 已经没人引用了，但历史订单还挂着它 —— 不算引用的话 A 会被回收，
                 * 于是所有旧订单的商品图集体裂掉。而订单是最不该被追溯性破坏的数据。
                 */
                of("ord_item", "cover", "订单商品快照", "sub_order_no"),

                // —— 售后与评价：用户传的凭证 ——
                of("ord_after_sale", "images", "售后 · 凭证图", "after_sale_no"),
                of("rvw_review", "images", "评价 · 图", "review_no"),
                of("rvw_appeal", "images", "差评申诉 · 举证图", "appeal_no"),
                // 评价里带的是下评价那一刻的头像快照，与 usr_account.avatar 指向同一张文件
                of("rvw_review", "avatar", "评价 · 头像快照", "review_no"),
                // 评价正文可能嵌图片地址
                of("rvw_review", "content", "评价 · 正文内嵌", "review_no"),

                // —— 用户 ——
                of("usr_account", "avatar", "用户 · 头像", "user_no"),

                // —— 营销 ——
                of("mkt_group_buy", "cover", "拼团 · 封面", "group_no"),
                of("mkt_request", "images", "求购 · 图", "request_no"),

                /*
                 * —— 内容中心 ——
                 * cnt_material.content 按注释就是「文案正文，或图片/视频地址」——
                 * 素材本身可能整列就是一个地址，帖子与问答则是正文里嵌地址。
                 * 三列都按自由文本扫：抠不出 key 的字符串匹配不上任何资产，代价为零；
                 * 而漏扫的代价是历史文章的配图集体裂掉。
                 */
                of("cnt_material", "content", "素材", "material_no"),
                of("cnt_post", "content", "社区帖子 · 正文内嵌", "post_no"),
                of("cnt_question", "content", "问答 · 正文内嵌", "question_no"),

                // —— 消息 ——
                // 模板与工单正文理论上不该有图，但它们是自由文本，扫一遍的成本可以忽略，
                // 而「理论上不该有」正是最容易出事的措辞
                of("notify_template", "content", "消息模板 · 正文内嵌", "template_no"),
                of("notify_ticket", "content", "工单 · 正文内嵌", "ticket_no"));
    }
}
