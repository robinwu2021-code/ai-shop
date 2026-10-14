import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";
createRequire(import.meta.url)("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion = async () => {};
const PORT=9420, CLI="/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PROJECT=resolve(process.cwd(),"dist/build/mp-weixin");
const OUT="/private/tmp/claude-501/-Users-robin-work-ai-ai-shop/ead18692-f8a9-46f3-888f-3c7dc05481ba/scratchpad";
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
await new Promise((res)=>{const p=spawn(CLI,["auto","--project",PROJECT,"--auto-port",String(PORT)],{stdio:["ignore","pipe","pipe"]});let d=false;const f=()=>{if(!d){d=true;res();}};p.stdout.on("data",()=>setTimeout(f,1500));setTimeout(f,12000);});
const mp=await automator.connect({wsEndpoint:`ws://localhost:${PORT}`});
for (const [route,name] of [["/pages/home/index","home2"],["/pages/address/index","list2"],["/pages/address-pick/index","pick2"]]) {
  try { await mp.reLaunch(route); } catch {}
  await sleep(7000);
  await mp.screenshot({ path: `${OUT}/mp-${name}.png` });
  console.log("shot", name);
}
await mp.disconnect();
