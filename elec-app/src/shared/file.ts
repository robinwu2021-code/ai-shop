// 选库存表。**小程序只能从微信聊天记录里选文件**（chooseMessageFile），这是工作台上要写
// 「在微信里发给自己」那三步的原因；H5 走浏览器的文件选择。页面不写 #ifdef，只调这里。
export interface PickedFile {
  path: string;
  name: string;
  size: number;
}

const EXT = ["xlsx", "xls", "csv"];

/** 小程序的全局对象：只用到用户目录 */
declare const wx: { env: { USER_DATA_PATH: string } };

export function pickSheet(): Promise<PickedFile | null> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.chooseMessageFile({
      count: 1,
      type: "file",
      extension: EXT,
      success: (res) => {
        const f = res.tempFiles[0];
        resolve(f ? { path: f.path, name: f.name, size: f.size } : null);
      },
      fail: (e) => (String(e?.errMsg ?? "").includes("cancel") ? resolve(null) : reject(new Error(e?.errMsg ?? "选文件失败"))),
    });
    // #endif
    // #ifndef MP-WEIXIN
    uni.chooseFile({
      count: 1,
      extension: EXT.map((x) => `.${x}`),
      success: (res) => {
        const files = res.tempFiles as unknown as { path: string; name: string; size: number }[];
        const f = files[0];
        resolve(f ? { path: (res.tempFilePaths as string[])[0] ?? f.path, name: f.name, size: f.size } : null);
      },
      fail: (e) => (String(e?.errMsg ?? "").includes("cancel") ? resolve(null) : reject(new Error(e?.errMsg ?? "选文件失败"))),
    });
    // #endif
  });
}

/**
 * 把下载到的表打开给他看。小程序：写进用户目录、openDocument（showMenu 让他能转发给自己电脑上的微信）；
 * H5：浏览器下载。页面不写 #ifdef，只调这里。
 */
export function openSheet(bytes: ArrayBuffer, name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    // 文件名里的斜杠会被当成子目录；其余字符 openDocument 都认
    const filePath = `${wx.env.USER_DATA_PATH}/${name.replace(/[\\/]/g, "_")}`;
    uni.getFileSystemManager().writeFile({
      filePath,
      data: bytes,
      success: () => uni.openDocument({
        filePath,
        fileType: "xlsx",
        showMenu: true,
        success: () => resolve(),
        fail: (e) => reject(new Error(e?.errMsg ?? "打开失败")),
      }),
      fail: (e) => reject(new Error(e?.errMsg ?? "保存失败")),
    });
    // #endif
    // #ifndef MP-WEIXIN
    const url = URL.createObjectURL(new Blob([bytes],
      { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    resolve();
    // #endif
  });
}
