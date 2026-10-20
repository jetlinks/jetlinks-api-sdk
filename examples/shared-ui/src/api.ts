/** Shared Example protocol. No platform-specific calls, credentials or language branches belong here. */
export interface Application {
 contextId:string; type:'api'|'business'; platformId:string; applicationId:string; apiBaseUrl:string;
 keyRevision:number; enabled:boolean; deleted:boolean; credentialReady:boolean
}
export interface Receipt {
 recordId:string; applicationId?:string; platformId?:string; eventId?:string; type?:string;
 receivedAt:number; verified:boolean; accepted:boolean; duplicate?:boolean; rawBody:string
}
export interface ServiceInfo {language:string; sdkVersion:string; features:string[]; syncStatus:string}
export async function api<T>(path:string,method='GET',body?:unknown,signal?:AbortSignal):Promise<T> {
 const response=await fetch('/example-api'+path,{method,headers:body===undefined?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal})
 const value=await response.json()
 if(!response.ok)throw new Error(value.error || `HTTP ${response.status}`)
 return value as T
}
export async function ndjson(path:string,body:unknown,onValue:(value:unknown)=>void,signal:AbortSignal) {
 const response=await fetch('/example-api'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal})
 if(!response.ok)throw new Error((await response.json()).error || `HTTP ${response.status}`)
 const reader=response.body!.getReader(),decoder=new TextDecoder()
 let pending=''
 try { while(true) { const {done,value}=await reader.read();pending+=decoder.decode(value,{stream:!done})
  if(pending.length>2*1024*1024)throw new Error('流响应单条数据超过限制')
  const lines=pending.split('\n');pending=lines.pop() || ''
  for(const line of lines)if(line.trim())onValue(JSON.parse(line))
  if(done){if(pending.trim())onValue(JSON.parse(pending));break}
 } } finally { await reader.cancel(); reader.releaseLock() }
}
