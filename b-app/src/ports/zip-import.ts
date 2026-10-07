// 压缩包导入商品图：选 zip → 解压 → 列文件 → 分类排序（归类逻辑在 @shared/ports/zip-media）。
//
// **解压是 App 原生能力**（plus.zip），小程序/H5 没有，所以这里按端分叉。
// 归类/排序是纯逻辑、已单测（zip-media.test.ts）；这个文件只管「把 zip 变成一堆本地路径」。
import { classifyZip, dropJunk, type ZipMedia } from "@shared/ports/zip-media";

// plus 是 App 运行时注入的全局，类型未在 @dcloudio 里导出，这里按 any 用
declare const plus: any;

/** 选一个 .zip 文件，返回它在应用缓存里的绝对路径。用户取消时 reject。 */
function pickZip(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef APP-PLUS
    // uni.chooseFile 在 App 运行时不存在，走原生插件（SAF 选文件 + 复制到缓存，见
    // 离线工程 ZipPickerModule）。requireNativePlugin 拿不到说明基座没打进这个模块。
    const zp = uni.requireNativePlugin("ZipPicker") as
      | { chooseZip: (cb: (res: { path?: string; cancel?: boolean; error?: string }) => void) => void }
      | undefined;
    if (!zp || typeof zp.chooseZip !== "function") {
      reject(new Error("当前基座不含文件选择插件，请用含 ZipPicker 的包"));
      return;
    }
    zp.chooseZip((res) => {
      if (res.cancel) reject(new Error("已取消"));
      else if (res.path) resolve(res.path);
      else reject(new Error(res.error || "没拿到文件"));
    });
    // #endif
    // #ifndef APP-PLUS
    reject(new Error("压缩包导入目前只支持 App"));
    // #endif
  });
}

/** 把 zip 解压到临时目录，返回解压目录与里面所有文件的本地绝对路径（递归）。 */
function decompress(zipPath: string): Promise<{ root: string; paths: string[] }> {
  return new Promise((resolve, reject) => {
    // #ifdef APP-PLUS
    const target = "_doc/goods-zip-" + Date.now() + "/";
    // 绝对文件路径要加 file:// 前缀，plus.zip 才认（_doc/_www 这类相对 URL 才不用）
    const src = zipPath.startsWith("/") ? "file://" + zipPath : zipPath;
    plus.zip.decompress(
      src,
      target,
      () => plus.io.resolveLocalFileSystemURL(target, (entry: any) => {
        const out: string[] = [];
        const walk = (dir: any, done: () => void) => {
          dir.createReader().readEntries((entries: any[]) => {
            let pending = entries.length;
            if (!pending) return done();
            for (const en of entries) {
              if (en.isDirectory) walk(en, () => { if (--pending === 0) done(); });
              else { out.push(en.fullPath); if (--pending === 0) done(); }
            }
          }, reject);
        };
        // 解压目录本身的绝对路径：清单里的路径减去它，才是包里的相对路径
        walk(entry, () => resolve({ root: String(entry.fullPath).replace(/\/$/, ""), paths: out }));
      }, reject),
      reject,
    );
    // #endif
    // #ifndef APP-PLUS
    reject(new Error("压缩包导入目前只支持 App"));
    // #endif
  });
}

let zipSupported = false;
// #ifdef APP-PLUS
zipSupported = true;
// #endif
/**
 * 这一端能不能导入压缩包（解压是 App 原生能力）。页面据此决定显不显示入口 ——
 * 条件编译只留在 ports/ 里，页面不写 #ifdef（design-tokens 守卫）。
 */
export const ZIP_IMPORT_SUPPORTED = zipSupported;

/** 压缩包里的一个文件：**相对路径**（包里的样子）+ 图片的宽高（txt、读不到时为空） */
export interface ZipEntry {
  path: string;
  width?: number;
  height?: number;
}

/** 解压后的压缩包 */
export interface ZipImport {
  /** 解压目录的绝对路径（末尾不带 /）。上传、读 txt 时用 `${root}/${path}` */
  root: string;
  /** 相对路径清单，系统垃圾已滤掉 */
  files: ZipEntry[];
  /** 规则分类（按相对路径算 —— 「根目录的 txt」只有相对路径才判得出来） */
  media: ZipMedia;
}

const IMAGE = /\.(jpe?g|png|webp|gif)$/i;

/** 本地图片的宽高。读不到不报错 —— 宽高只是给模型的线索 */
function sizeOf(path: string): Promise<{ width?: number; height?: number }> {
  return new Promise((resolve) => {
    uni.getImageInfo({
      src: path,
      success: (r) => resolve({ width: r.width, height: r.height }),
      fail: () => resolve({}),
    });
  });
}

/** 选 zip → 解压 → 相对路径清单（带宽高）+ 规则分类。上传交给上层。 */
export async function importZip(): Promise<ZipImport> {
  const zip = await pickZip();
  const { root, paths } = await decompress(zip);
  const rel = dropJunk(paths.map((p) => (p.startsWith(root + "/") ? p.slice(root.length + 1) : p)));
  const files: ZipEntry[] = [];
  for (const path of rel) {
    files.push(IMAGE.test(path) ? { path, ...(await sizeOf(`${root}/${path}`)) } : { path });
  }
  return { root, files, media: classifyZip(rel) };
}

/** 读解压出的 txt 文本内容（商品文字）。App 用 plus.io；读失败返回 undefined。 */
export function readTextFile(path: string): Promise<string | undefined> {
  return new Promise((resolve) => {
    // #ifdef APP-PLUS
    plus.io.resolveLocalFileSystemURL(path, (entry: any) => {
      entry.file((file: any) => {
        const reader = new plus.io.FileReader();
        reader.onloadend = (e: any) => resolve(typeof e.target.result === "string" ? e.target.result : undefined);
        reader.onerror = () => resolve(undefined);
        reader.readAsText(file, "utf-8");
      }, () => resolve(undefined));
    }, () => resolve(undefined));
    // #endif
    // #ifndef APP-PLUS
    resolve(undefined);
    // #endif
  });
}
