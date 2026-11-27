package ai.neargo.shop.merchant.media;

import static ai.neargo.shop.media.MediaRefColumn.onPlatform;

import ai.neargo.shop.media.MediaRefColumn;
import ai.neargo.shop.media.MediaRefSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 平台基础库上的图片列：主体标识、资质影像、门店背景图、品牌标识、用户头像。
 *
 * <p>从商户与核心的两份登记里挪出来、放在平台句柄下的代码里，并标明在平台基础库：
 * 回收扫描器按这个标记走平台句柄的连接。分库之后如果还走主库，读到的是旧表，
 * 新上传的图会被当成没人引用而删掉。
 */
@Component
public class PlatformMediaRefs implements MediaRefSource {

    @Override
    public List<MediaRefColumn> columns() {
        return List.of(
                onPlatform("mch_entity", "logo", "商家 · logo", "entity_no"),
                /*
                 * 审核入库后的正式证件记录。进件时提交的那一批在主库的进件申请上，仍在商户的那份登记里 ——
                 * 两处都要在，只登记一处的话，被驳回或还没过审的申请里那些证件图会被判成孤儿。
                 */
                onPlatform("mch_qualification", "image_url", "商家资质 · 证件影像", "qual_no"),
                // 门店背景图（V368）。漏登记的话它会被当成孤儿回收：门户顶部突然变回浅底，店主以为自己没设过
                onPlatform("mch_store", "banner_url", "门店 · 背景图", "store_no"),
                // 品牌即使没有门店或商品复用，也保留它上传的标识图
                onPlatform("mch_brand", "logo", "品牌 · 标识图片", "brand_no"),
                onPlatform("usr_account", "avatar", "用户 · 头像", "user_no"));
    }
}
