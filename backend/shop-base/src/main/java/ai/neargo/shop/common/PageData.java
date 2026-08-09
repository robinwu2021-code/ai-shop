package ai.neargo.shop.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页包 {@code {records, total, page, size}}（与 c-app 契约一致，见 {@link ApiResult} 的说明）。
 *
 * <p>字段名刻意与 MyBatis-Plus {@code IPage} 的 {@code records} 对齐，
 * 这样 {@link #of(IPage)} 是一次直译，不需要在每个 Service 里手抄四个字段。
 */
public record PageData<T>(List<T> records, long total, long page, long size) {

    public static <T> PageData<T> of(IPage<T> p) {
        return new PageData<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public static <T> PageData<T> of(List<T> records, long total, long page, long size) {
        return new PageData<>(records, total, page, size);
    }

    public static <T> PageData<T> empty(long page, long size) {
        return new PageData<>(List.of(), 0, page, size);
    }
}
