package ai.neargo.shop.gateway.core.function;

import java.util.List;

/**
 * 一个模块声明自己提供哪些功能点（TDD-网关层与统一权限 §2.6）。
 *
 * <p>模块装配了才有这个 bean；没装配的模块没有声明，它的功能点在登记表里就不存在。
 */
public interface FunctionModule {

    /** 模块编码：retail / pay / inventory … */
    String code();

    List<FunctionPoint> points();
}
