export function fixedMinor(raw:string,scale:number):number {
 if(!Number.isInteger(scale)||scale<0||scale>3||!/^\d+(\.\d+)?$/.test(raw))throw Error('amount');
 const [whole,fraction='']=raw.split('.');if(fraction.length>scale)throw Error('amount');
 const value=Number(whole)*10**scale+Number(fraction.padEnd(scale,'0'));
 if(!Number.isSafeInteger(value))throw Error('amount');return value;
}
export function dateFilter(start:string,end:string):{startDate?:string;endDate?:string} {
 for(const value of [start,end])if(value&&(!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value))throw Error('date');
 if(start&&end&&start>end)throw Error('date');return {...(start?{startDate:start}:{}),...(end?{endDate:end}:{})};
}
export interface Progress { rechargeNo:string;revision:number;status:string }
export function createRechargeProgress<T extends Progress>(service:{create:()=>Promise<T>;submit:(r:T)=>Promise<T>;receive:(r:T)=>Promise<T>;query:(no:string)=>Promise<T>},onChange:(r:T)=>void=()=>{},initial:T|null=null,active:()=>boolean=()=>true) {
 let current:T|null=initial;let busy=false;
 const save=(r:T)=>{current=r;onChange(r);return r;};
 return {get current(){return current;},async advance(){
  if(busy)throw Error('busy');busy=true;
  try{if(!active())throw Error('inactive');if(!current)save(await service.create());if(!active())throw Error('inactive');if(current!.status==='WAIT_COLLECTION')save(await service.submit(current!));if(!active())throw Error('inactive');if(current!.status==='SUBMITTED')save(await service.receive(current!));return current!;}
  finally{busy=false;}
 },async refresh(){if(!current)throw Error('unknown');return save(await service.query(current.rechargeNo));}};
}
