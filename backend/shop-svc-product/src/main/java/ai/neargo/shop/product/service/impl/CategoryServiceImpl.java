package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.product.service.CategoryService;

import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Override
    public List<CategoryVO> tree() {
        // 一次查全量再在内存里建树：类目通常几十条，三次分层查询反而更慢
        List<PrdCategory> all = categoryMapper.selectList(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getStatus, "ACTIVE")
                .orderByAsc(PrdCategory::getSort));

        Map<String, List<PrdCategory>> byParent = all.stream()
                .filter(c -> c.getParentNo() != null && !c.getParentNo().isBlank())
                .collect(Collectors.groupingBy(PrdCategory::getParentNo));

        return all.stream()
                .filter(c -> c.getParentNo() == null || c.getParentNo().isBlank())
                .sorted(Comparator.comparingInt(c -> nz(c.getSort())))
                .map(c -> toVO(c, byParent))
                .toList();
    }

    private CategoryVO toVO(PrdCategory c, Map<String, List<PrdCategory>> byParent) {
        List<CategoryVO> children = byParent.getOrDefault(c.getCategoryNo(), List.of()).stream()
                .sorted(Comparator.comparingInt(x -> nz(x.getSort())))
                .map(x -> toVO(x, byParent))
                .toList();
        return new CategoryVO(c.getCategoryNo(), c.getParentNo(), nz(c.getLevel()),
                c.getName(), c.getIcon(), nz(c.getSort()), children);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
