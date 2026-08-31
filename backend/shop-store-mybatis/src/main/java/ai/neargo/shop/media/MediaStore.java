package ai.neargo.shop.media;

import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;

/**
 * 「把字节存到某处，并给回一个能打开的 URL」—— 就这一件事。
 *
 * <p>一期是本地磁盘（{@code LocalDiskMediaStore}），二期换腾讯云 COS。
 * <b>换的时候只动实现类与端上的图片域名</b>：目录结构、{@link SysMediaAsset} 记账、
 * 扫描与回收逻辑、业务表里存的相对路径，四样一行都不用改
 * （见 TDD-图片存储与空间回收 §L3-9）。
 *
 * <p>接口住在 {@code shop-base} 而实现住在 {@code shop-channel}：
 * 与支付通道同一个位置逻辑 —— 这是外部适配，不是业务判断。
 *
 * <p><b>公开读与签名读必须分开</b>（{@link #publicUrl} vs {@link #signedUrl}）：
 * 商品图就是要给买家看的，而营业执照不是。一期之前这两类落在同一个
 * {@code permitAll} 的目录下，URL 一旦泄露谁都能拉到证件原件 ——
 * 分成两个方法是为了让「这张图该怎么给出去」在调用点就必须回答，而不是默认公开。
 */
public interface MediaStore {

    /**
     * 落字节。<b>调用方必须先写好 {@code PENDING} 记账行再调它</b> ——
     * 反过来会产生「磁盘有文件、库里没有」的孤儿，而孤儿查不出来：
     * 统计永远少算，回收清单里永远不出现。
     *
     * @param key 相对路径，形如 {@code E0001/S0003/goods/202608/9f2c….jpg}
     */
    void put(String key, InputStream in, long size, String contentType);

    /**
     * 删字节。<b>幂等</b>：已不存在的 key 直接跳过，不抛异常 ——
     * 回收批次失败后要能整批重跑。
     */
    void delete(Collection<String> keys);

    /**
     * 字节是否真的在。
     *
     * <p>给对账用：一条停在 {@code PENDING} 的记账行，只有问过这个问题才知道
     * 该补成 {@code ACTIVE}（落盘成功、只是第三步没走到）还是该删掉（根本没落盘）。
     */
    boolean exists(String key);

    /** 公开可读的稳定路径。只给 {@link SysMediaAsset#GOODS}。 */
    String publicUrl(String key);

    /**
     * 私有资产的<b>稳定</b>路径。给 {@link SysMediaAsset#QUAL} 与 {@link SysMediaAsset#AFTERSALE}。
     *
     * <p><b>存进业务表的是它，不是 {@link #signedUrl} 的结果</b> ——
     * 签名带有效期，存进 {@code mch_qualification.image_url} 那种字段就是一颗定时炸弹：
     * 存的时候能打开，几分钟后同一行数据就变成死链，而且没有任何报错。
     *
     * <p>所以分工是：<b>存的是稳定路径，签名是读取那一刻的事。</b>
     * 这也正是「业务表里只存相对路径、换域名不洗表」那条的延伸。
     */
    String privatePath(String key);

    /**
     * 在 {@link #privatePath} 上加签，渲染那一刻才生成。
     *
     * <p>为什么不是「走一个带鉴权的接口」：小程序的 {@code <image src>} 与浏览器的
     * {@code <img>} <b>都没法带请求头</b>，鉴权接口在这两个地方直接用不了。
     * 签名 URL 把凭证放进 URL 本身，这也正是 COS 的做法 —— 一期照着它做，切换时语义不变。
     */
    String signedUrl(String key, Duration ttl);
}
