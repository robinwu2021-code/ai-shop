# ZipPicker 原生插件（App 选 .zip 文件）

uni.chooseFile 在 uni-app App 运行时不存在，商品快速录入的「导入压缩包」
在 App 上靠这个原生 UniModule：SAF 选文件 → 复制 content URI 到应用缓存 →
把绝对路径回给 JS（见 b-app/src/ports/zip-import.ts）。

**仓库里这份是真源**：`b-app/offline/build-apk.sh` 每次打包（⓪b）先把两份文件同步进仓库外的离线工程，
改插件只改这里。落点：
- `ZipPickerModule.java` → `simpleDemo/src/main/java/top/hxmall/bapp/plugin/`
- `dcloud_uniplugins.json` → `simpleDemo/src/main/assets/`

依赖已在离线工程 build.gradle：uniapp-v8-release.aar（UniModule）、fastjson 1.2.83。
