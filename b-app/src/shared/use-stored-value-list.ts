import { ref,watch,onUnmounted } from 'vue';
import type { SvPage } from '@/api/stored-value-types';
/** A store/filter change invalidates in-flight pages before their response can be displayed. */
export function useStoredValueList<T>(store:()=>string,fetch:(cursor?:string)=>Promise<SvPage<T>>) {
 const records=ref<T[]>([]);const cursor=ref<string|null>(null);const loading=ref(false);const error=ref('');let generation=0;
 async function load(more=false){
  if(more&&(loading.value||!cursor.value))return;
  const token=more?generation:++generation;const originalStore=store();if(!originalStore)return;
  if(!more){records.value=[];cursor.value=null;}loading.value=true;error.value='';
  try{const page=await fetch(more?cursor.value!:undefined);if(token!==generation||originalStore!==store())return;
   records.value=(more?[...records.value,...page.records]:page.records) as typeof records.value;cursor.value=page.nextCursor;
  }catch(e){if(token===generation&&originalStore===store())error.value=(e as Error).message;}
  finally{if(token===generation)loading.value=false;}
 }
 function invalidate(){generation++;records.value=[];cursor.value=null;loading.value=false;error.value='';}
 watch(store,()=>{invalidate();void load();});onUnmounted(invalidate);
 return {records,cursor,loading,error,load,invalidate};
}
