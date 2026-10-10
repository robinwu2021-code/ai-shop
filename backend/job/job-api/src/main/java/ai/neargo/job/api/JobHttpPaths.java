package ai.neargo.job.api;

/**
 * 调度器调业务侧的两个内部端点。**服务端 mapping 与客户端 {@code @HttpExchange} 引用同一份常量**，
 * 路径漂了就编译不过 —— 此前两边各写一份字面量，一边改了另一边只会在运行时收到 404。
 *
 * <p>只是字符串常量：这个模块一个依赖都不能加（见 pom 注释），常量不需要任何依赖。
 */
public final class JobHttpPaths {

    /** 业务侧声明了哪些任务 */
    public static final String DECLARATIONS = "/internal/job/declarations";

    /** 触发一个任务。路径变量名 {@code handlerName} 两边都按这个名字取 */
    public static final String RUN = "/internal/job/{handlerName}/run";

    /** 请求头：调度器与业务侧之间的共享密钥 */
    public static final String TOKEN_HEADER = "X-Job-Token";

    private JobHttpPaths() {
    }
}
