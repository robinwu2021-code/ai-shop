# 餐饮独立Mock服务联调

## 0 需求与验收条件

档位1：只增加显式测试配置与启动方式，不新增业务端点、表或权限。依据：餐饮后端待办REM-07，用户确认“保持现有接口边界，先完成独立Mock与已有合同联调”。

- AC1：默认同进程模式保持；设置远程地址后不启动本地平台，Gateway仍调用真实HTTP。
- AC2：独立JVM能由服务包启动，使用环境变量接收测试token/目录/端口，不启动业务数据库。
- AC3：通过HTTP准备外部订单、发送真实签名/错误签名及重复回调；受理后响应失败，重启可按原操作查回。
- AC4：原有宿主营业场景可以连接该独立Mock，在MySQL9临时副本运行，保留原业务所有者及权限。业务场景发现的历史缺口须明确记录，不伪造成功。
- AC5：不扩展独立CIM生产合同；分别记录已有独立合同与集成营业流程，完整四进程闭环仍后置。

## 1 目标与范围

沿现有商品/订单/账号/主体/门店、FOOD、CIM和MySQL9边界验证服务端菜单/外部订单/回调/厨房/履约。真实平台及资金不接入，不改生产开关。业务数据通过原API/Service准备；外部数据通过Mock管理HTTP录入，不直接造业务订单或库存凭据。

现有MockCommercePlatform是真实回环HTTP服务器、原文件化fixtures/operations持久化；当前默认由CommerceMockAutoConfiguration在CIM进程内启动。新增独立进程入口复用同一实现，不另做模拟引擎。独立CIM当前只开放内部合同（CommerceApplication），商家BFF在平台；用户已确认本批不扩展生产合同，仅完成独立Mock及已有合同部分。

## 2 代码与执行计划

- [x] CommerceMockApplication：无业务数据库的普通Java入口，环境配置目录/token/port，启动原MockCommercePlatform及正常关闭。使用现有commerce-server可执行JAR的PropertiesLauncher运行，不新增模块或发行依赖。
- [x] CommerceMockAutoConfiguration：可配置shop.commerce.mock.base-url；非空时仅创建原HTTP Gateway，不启动同进程Mock。默认配置保持；commerce-mock、显式enabled及禁止生产配置的原边界保持。
- [x] 相关配置测试：默认嵌入模式、外部模式真实HTTP交互、profile隔离及生产拒绝，不安装替代业务owner。
- [x] 独立进程验证用例：运行固定副本JAR，使用临时目录/测试token，本机回环HTTP创建fixtures和外部订单，验证签名回调、重复、失败/不确定及重启持久化；具体操作沿既有协议，不声称每个Scenario适用于每个接口。
- [x] 服务端业务联调：隔离MySQL模板副本，先运行原菜单/订单/厨房/履约纵向场景接独立Mock；分别记录FOOD/CIM独立合同与完整四进程覆盖，未执行部分不能合并宣称完成。
- [x] 所有本批编码及构建完成后集中相关测试，仅定向复测修复。记录产物哈希、临时数据清理、启动拓扑及限制；不部署。

## 3 关键约束

Mock只模拟外部角色。现有Gateway仍发实际HTTP；签名、原业务校验、幂等、事务、查询回执和状态机保持。目录/token均为测试私有配置，token不进入仓库或命令行；实例只绑定回环地址。状态变化依据实际原回执，不把QUEUED/SENT当完成。独立CIM已有边界不能为了测试而开放未授权BFF或绕过权限；若用户确认生产合同扩展，再沿现有内部API/客户端方案实施。


## 4 操作方式与代码清单

配置项：`shop.commerce.mock.base-url` 默认空；空值沿原目录/token/port启动内嵌Mock；非空值连接指定回环地址，不要求内嵌目录。仍须`commerce-mock` profile及显式enabled=true，生产profile禁止。此处“远程”指同机独立进程，原Gateway只允许回环HTTP，不扩展公网能力。

独立入口复用commerce-server发行包的PropertiesLauncher；环境变量COMMERCE_MOCK_DIRECTORY（持久化目录）、COMMERCE_MOCK_TOKEN（私有测试token，至少32字符）、COMMERCE_MOCK_PORT（默认9410，0为随机端口）。目录保留即可在重启后查回。启动命令：
```sh
java -Dloader.main=ai.neargo.shop.commerce.channel.mock.CommerceMockApplication \
  -cp /absolute/immutable-commerce-server.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

- commerce-channel/mock/CommerceMockApplication.java：独立入口和停机。
- commerce-server/pom.xml：沿用既有repackage执行id，避免重复打包。
- commerce-channel/mock/CommerceMockAutoConfiguration.java：按地址选择内嵌或独立Mock，原HTTP Gateway不变。
- CommerceMockAutoConfigurationTest：默认/独立/profile/生产隔离。
- MockCommerceProcessTest：从环境变量COMMERCE_MOCK_TEST_JAR取得已构建包，复制不可变JAR，第二JVM启动和重启。
- FoodChannelMockEndpoint、FoodChannelVerticalFixture/Provisioning/NativeIT：测试环境FOOD_CHANNEL_VERTICAL_MOCK_BASE_URL选择独立地址，未设置沿原内嵌模式。
- scripts/test-food-channel-independent-mock.py：固定JAR、MySQL9测试模板的临时CORE/INV副本、私有测试token、原营业场景执行及清理。只保留测试日志及Mock状态，无生产开关变更。

编译使用既有food-verify profile提供宿主原生测试所需food-channel测试依赖。过程测试通过`-Dtest=CommerceMockAutoConfigurationTest,MockCommerceProcessTest`定向执行；原营业场景用脚本：
```sh
python3 scripts/test-food-channel-independent-mock.py \
  --mysql-defaults /private/mysql-client.cnf --template food_host_smoke_run20261029f \
  --commerce-jar /absolute/commerce-server.jar
```

Mock管理HTTP仍是原/admin/fixtures、/admin/orders、/admin/events/emit等合同，均需Bearer测试token。脚本不直写业务订单/库存凭据。过程用例的回调接收器只证明签名、传输与重复次数，不代替CIM业务去重或厨房验收。PERSIST_THEN_TIMEOUT实际可能是持久化后503响应，验收称“响应失败/不确定”，不声称TCP超时。

## 5 验收结果

- 集中构建：宿主、commerce-server、food-server及相关模块package成功（food-embedded/food-verify/commerce-embedded/food-commerce-embedded/retail-commerce-embedded）。最初漏启用food-verify使既有库存测试无法导入food-channel，改用原profile后通过，无需改原测试或生产依赖。
- AC1：CommerceMockAutoConfigurationTest的defaultModeStartsEmbeddedPeer、externalModeNeedsNoEmbeddedDirectoryAndCallsExistingHttpContract、externalModeStillRejectsProduction、ordinaryDeploymentHasNoMockBeans，4通过、0跳过。日志/private/tmp/food-mock-targeted.log保留首次测试及后续打包失败记录。
- AC2/AC3：MockCommerceProcessTest.packagedPeerSeedsOrdersSignsCallbacksAndRetainsUncertainEffectsAfterRestart，1通过、0跳过，日志/private/tmp/food-mock-process-retest.log。第二JVM执行发行包，无Spring/数据库；管理HTTP录入外部订单、回调重复2次、错误签名返回401、菜单/接单先不确定后重启查回确认。回调接收器是用例协议接收端，不代表生产CIM已去重。
- 初次过程测试发现发行包含旧渠道JAR导致入口ClassNotFound。commerce-server继承执行与匿名执行重复repackage，按food-server既有做法统一execution id=repackage；依赖更新的增量构建用-Dmaven.jar.forceCreation=true生成新包，执行次数由2变1。只复测受影响过程用例，未重复已通过的4项。
- 本批联调固定commerce包SHA256：f6a8b5f4964cfe2c38bce53d15458387364016971362372e292d695256f0f424。food包：915078b3dd3e1db61cb439c6804ef2c5dd71c498e171f9ca70bfb9de01a1d4eb。
- 代码审查发现测试启动readLine缺实际期限，已加20秒Future期限及失败终止/等待/关闭；审查定向复核通过。
- AC4：FoodChannelVerticalNativeIT.originalOwnersConserveTheInvoiceAndQueryAnUncertainAcceptanceWithoutSendingAgain，实际原宿主＋独立Mock JVM＋MySQL9副本，1通过、0跳过（116.3秒）。菜单发布/映射、签名回调、共享订单与FOOD准备、正常/不确定接单查回、厨房/打印/履约、取消源落库边界均走原所有者。日志/private/tmp/food-channel-independent-retest.log及/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/food-channel-mock-6vdq9wtt/vertical.log。首次运行失败于夹具recoverDue(1)假设新回执排第一；只读模板确认17条WAITING_OWNER、1条WAITING_CANCELLATION到期记录，改用原100上限后仅复测该方法通过。首次日志保留于food-channel-independent-run.log及food-channel-mock-phg0ihgy/vertical.log。未放宽目标订单快照/源落库断言。
- AC5：verify-food-standalone-mysql.py实际运行FOOD与CIM两个独立发行包、另一MySQL9副本，通过签名缺失401、越店403、本地物料创建/调整10.123456及幂等查回、CIM现有authority缺失404、FOOD操作系统进程重启后原键/余额保持。日志/private/tmp/food-mock-existing-contracts.log，进程日志food-standalone-wje6s2gh。此合同专项未配置Mock，不与AC4拼接宣称四进程营业闭环。
- 最终不同JUnit方法6项通过（配置4＋独立Mock过程1＋原营业场景1），独立合同脚本另计1次通过。没有全仓测试或重复运行已通过的无关用例。运行脚本自动停止所建进程并删除UUID测试库；没有生产部署。
- 剩余边界：独立宿主/FOOD/CIM/Mock四进程完整营业需要的生产合同另行设计，本批按用户确认保留；真实门店期初/启用、真实第三方与资金、后置报表来源不在本批。



## 6 同步边界

本批业务提交fc764efa6。远端同期新增1726ee1ce、60a614575进销存提交，正常合入为171e66853，没有覆盖或强推。上述原生营业证据的库存副本由INV18迁移到INV25；远端新增INV26/27不属于这份原生验收证据。合入后只编译相关宿主及独立服务，不据此宣称新库存迁移已验证；不重复完整营业专项。推送沿既有授权使用SKIP_COMPILE_GATE=1，仅远端备份，不部署。合并后相关模块package成功（BUILD SUCCESS，/private/tmp/food-mock-merged-build.log）；测试未重复。
