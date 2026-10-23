# Windows 打印代理

统一 Maven 构建完成后运行 `build-windows.ps1`，默认生成 app-image。EXE/MSI 需要 Windows JDK 21 和匹配的 WiX；不能用 macOS 产物代替 Windows 安装验收。

1. 复制 `agent.properties.example` 到当前 Windows 用户的私有配置目录，填写 HTTPS 服务地址、代理编号及设备物理标识/队列。
2. 用打包的启动器运行 `agent.properties --init`，将输出的公钥粘贴到商家端代理配对页。
3. 将商家端返回的加密配对材料写入本地临时文件，运行 `agent.properties --enroll 配对材料文件`。配对完成后删除该临时文件。
4. 不带额外参数启动代理；心跳上报登记队列后，在商家端添加本地打印机，再将生成的 printerNo 填入 `printerNos`。
5. 使用 `register-startup.ps1` 为同一 Windows 用户配置登录启动。DPAPI 凭据不能复制到其他用户或改为 LocalSystem 使用。

代理在 SQLite 中先提交 SUBMITTING 和事件，再向系统队列投递。Java Print Service 接收任务只报 ACCEPTED；没有可靠设备证据时保留 UNKNOWN，需要现场核实。重启不会重新提交原 SUBMITTING 记录，也不会使用清空整个队列的方式处理单票取消。SQLite 数据、私钥和访问凭据不得作为普通附件导出。

当前已写安装配置；Windows 打包、安装和实际打印均待实机环境验收。
