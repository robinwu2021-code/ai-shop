# 餐饮独立库存同步投递

## 0 需求对齐

依据统一实施方案08的M03/M04：独立FOOD只生产、宿主唯一投递；宿主不依赖food-core，库存同步原键及回执语义不变。现状缺少宿主远程handler与独立生产端路由，本批补齐，不新增第二个投递器。

## 1 契约

新增POST `/internal/food/stock-sync/dispatch`，输入entityNo、storeNo、envelope（原OutboxEnvelope全部字段）；输出原DeliveryResult(kind/errorCode/detail)。沿共享内部token和X-Food-Scope签名；明文scope必须与原envelope.payload里的范围相同，再由原分发器核验持久化意图。缺/错签名401、主体门店不符403。无新增表、权限码或生产配置。

## 2 执行清单

1. food-api增加内部API与分发Service合同，FoodOutboxRouting登记稳定库存handler与生产端路由。
2. 原FoodStockSyncDispatcher实现合同并核验外层scope；原embedded handle路径不变。
3. food-server内部Controller委托同一分发器。food-web增加无持久化远程handler，沿既有FoodStockClientConfiguration和签名器装配；超时/远端不可用可重试，原事件不重新编号。
4. 一次性完成相关测试代码后统一编译，再集中执行路由、MVC签名、typed远程、实际TCP/原生MySQL相关场景；不重跑无关全仓测试。
5. 独立服务产物/运行检查按实际结果登记；若全拓扑未覆盖，保留待办，不将部分进程证明为全部独立部署完成。

## 5 验收

宿主standalone仅使用远程handler，food-core仍被构建约束禁止；独立FOOD producer登记库存目标且无runner；原键、hash、payload完全保留；越权范围在进入业务处理前拒绝；服务故障不标记成功，原持久化待办可恢复。数据库0变更；真实门店/生产开关不动。最终命令、结果与剩余边界登记统一实施07。


## 6 实际代码对账

除第2节外，统一构建发现inventory-svc继承的repackage与未命名执行重复，覆盖普通测试依赖JAR，已统一为repackage执行ID；前批2个引用food-core的原生夹具迁到embedded专用目录。实际JAR验收脚本仅使用本机MySQL9测试模板副本，无状态内部服务显式排除未使用的Redis自动装配。数据库和生产配置均未变。最终证据集中登记统一实施07，API/代码清单同步02/03。

验收状态：本批编码与相关验证完成，21项不同JUnit通过，固定实际JAR/MySQL9场景通过。真实TCP发现的错误状态覆盖已通过框架ERROR分派许可修复，普通/error入口仍受保护。完整宿主及Mock渠道业务拓扑未在本批脚本覆盖，保留后续任务；详统一实施07。
