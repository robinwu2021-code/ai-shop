// 选库存表。**小程序只能从微信聊天记录里选文件**（chooseMessageFile），这是工作台上要写
// 「在微信里发给自己」那三步的原因；H5 走浏览器的文件选择。页面不写 #ifdef，只调这里。
export interface PickedFile {
  path: string;
  name: string;
  size: number;
}

const EXT = ["xlsx", "xls", "csv"];

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
