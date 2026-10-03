package ai.neargo.shop.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * {@code IPage} → {@link PageData} 的直译。
 *
 * <p>这个方法原本是 {@code PageData} 的一个静态工厂。它只有三行，
 * 却让整个分页契约<b>必须带着 MyBatis 才能用</b> ——
 * 而 {@code PageData} 是三端共用的返回壳，job 与将来的 pay 都要用它。
 *
 * <p>搬到这里之后 {@code shop-base} 就干净了：想要这次直译的人依赖
 * {@code shop-store-mybatis}，只想要分页壳的人不必。
 *
 * <p><b>为什么不做成 PageData 的重载而是单开一个类</b>：重载放在
 * {@code shop-base} 里就还是同一个问题（编译期就要 IPage），
 * 放在这里又不能给一个 record 加方法。单开一个工具类是唯一干净的形状。
 */
public final class MybatisPages {

    private MybatisPages() {
    }

    public static <T> PageData<T> of(IPage<T> p) {
        return new PageData<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }
}
