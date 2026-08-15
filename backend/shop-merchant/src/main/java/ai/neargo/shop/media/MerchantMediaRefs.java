package ai.neargo.shop.media;

import org.springframework.stereotype.Component;

import java.util.List;

import static ai.neargo.shop.media.MediaRefColumn.of;

/** shop-merchant 把图片地址放在哪些列里 —— 主体 logo 与两类证件。 */
@Component
public class MerchantMediaRefs implements MediaRefSource {

    @Override
    public List<MediaRefColumn> columns() {
        return List.of(
                of("mch_entity", "logo", "商家 · logo", "entity_no"),

                /*
                 * 两处证件必须都在：
                 *   mch_entity_apply.qualifications  进件时提交的那一批（JSON 数组）
                 *   mch_qualification.image_url      审核入库后的正式记录
                 * 只登记后者的话，被驳回或还没过审的申请里那些证件图会被判成孤儿 ——
                 * 而补料的人回来一看，自己刚传的执照没了。
                 */
                of("mch_entity_apply", "qualifications", "进件 · 资质图", "apply_no"),
                of("mch_qualification", "image_url", "商家资质 · 证件影像", "qual_no"),

                /*
                 * 店招审核的 content：kind=BANNER 时是图片 URL，kind=NOTICE 时是公告文本。
                 * 不按 kind 分开扫 —— 按自由文本一起扫，文本抠不出 key 也就匹配不上任何资产。
                 * 分开扫要多一个条件，而条件写错的后果是店招图被回收。
                 */
                of("mch_store_audit", "content", "店招审核 · 内容", "audit_no"));
    }
}
