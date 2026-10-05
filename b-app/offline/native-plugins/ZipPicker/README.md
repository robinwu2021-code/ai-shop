# ZipPicker 原生插件（App 选 .zip 文件）

uni.chooseFile 在 uni-app App 运行时不存在，商品快速录入的「导入压缩包」
在 App 上靠这个原生 UniModule：SAF 选文件 → 复制 content URI 到应用缓存 →
把绝对路径回给 JS（见 b-app/src/ports/zip-import.ts）。

**离线工程在仓库外，这里是留档。重建/换工程时把两份文件放回：**
- `ZipPickerModule.java` → `simpleDemo/src/main/java/top/hxmall/bapp/plugin/`
- `dcloud_uniplugins.json` → `simpleDemo/src/main/assets/`

依赖已在离线工程 build.gradle：uniapp-v8-release.aar（UniModule）、fastjson 1.2.83。
