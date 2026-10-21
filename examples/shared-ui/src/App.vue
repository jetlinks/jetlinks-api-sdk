<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch } from 'vue'
import { api,ndjson,type Application,type Receipt,type ServiceInfo } from './api'
import JsonPanel from './JsonPanel.vue'
import { createRequestScope } from './requestScope'
const info=ref<ServiceInfo>(),apps=ref<Application[]>([]),selected=ref(''),tab=ref('call'),error=ref(''),busy=ref(false)
const app=computed(()=>apps.value.find(a=>a.contextId===selected.value))
const endpoint=computed(()=>'/applications/'+encodeURIComponent(selected.value))
const authMode=ref('oauth2'),client=ref('reactive'),method=ref('GET'),path=ref('/authorize/context'),body=ref(''),stream=ref(false),result=ref<unknown>()
const clientModes=computed(()=>info.value?.clientModes || [{value:'reactive',label:'JetLinksReactiveClient'},{value:'sync',label:'JetLinksClient'}])
watch(clientModes,modes=>{if(!modes.some(mode=>mode.value===client.value))client.value=modes[0]?.value || ''})
const diagnostics=ref<unknown>(),receipts=ref<Receipt[]>([]),receipt=ref<Receipt>(),page=ref(0),total=ref(0),allApps=ref(true),poll=ref(true)
const eventType=ref(''),eventId=ref(''),receiptStatus=ref('')
const session=ref<unknown>(),authorization=ref<unknown>(),commands=ref<unknown>(),subscriptionResult=ref<unknown>()
const operation=ref('query'),subscriptionId=ref(''),subscriptionBody=ref('{}'),taskId=ref(''),attempt=ref(1)
const roleId=ref(''),roleOperation=ref('roleAuthorization')
const requests=createRequestScope()
let timer:ReturnType<typeof setInterval>|undefined,refreshing=false
async function run(work:()=>Promise<void>){
 await requests.run('operation',async task=>{
  error.value='';busy.value=true
  try{await work()}catch(e){task.commit(()=>{error.value=e instanceof Error?e.message:String(e)})}
  finally{task.commit(()=>{busy.value=false})}
 })
}
async function load<T>(key:string,path:string,apply:(value:T)=>void,method='GET',body?:unknown){
 await requests.run(key,async task=>{
  const value=await api<T>(path,method,body,task.signal)
  task.commit(()=>apply(value))
 })
}
async function refreshApps(){apps.value=await api<Application[]>('/applications');if(!selected.value&&apps.value.length)selected.value=apps.value[0].contextId}
async function refreshReceipts(){
 const query=new URLSearchParams({pageIndex:String(page.value)})
 if(eventType.value.trim())query.set('type',eventType.value.trim())
 if(eventId.value.trim())query.set('eventId',eventId.value.trim())
 if(receiptStatus.value)query.set('status',receiptStatus.value)
 if(!allApps.value&&app.value){query.set('applicationId',app.value.applicationId);query.set('platformId',app.value.platformId);if(app.value.type==='api')query.set('channelId',app.value.contextId)}
 await load<{data:Receipt[];total:number}>('receipts','/notifications?'+query,response=>{receipts.value=response.data;total.value=response.total})
}
async function loadReceipt(item:Receipt){await load<Receipt>('receipt','/notifications/'+encodeURIComponent(item.recordId),value=>{receipt.value=value})}
async function refreshDiagnostics(){if(app.value)await load('diagnostics',endpoint.value+'/diagnostics',value=>{diagnostics.value=value},'POST',{authMode:authMode.value})}
async function invoke(){
 await requests.run('request',async task=>{
  result.value=undefined
  const target=endpoint.value
  const payload={method:method.value,path:path.value,body:body.value.trim()?JSON.parse(body.value):undefined,client:client.value,authMode:authMode.value,stream:stream.value}
  if(stream.value){
   const values:unknown[]=[];result.value=values
   await ndjson(target+'/requests',payload,value=>{task.commit(()=>{values.push(value);if(values.length>100)values.shift();result.value=[...values]})},task.signal)
  }else{
   const value=await api(target+'/requests','POST',payload,task.signal)
   task.commit(()=>{result.value=value})
  }
  if(task.current())await refreshDiagnostics()
 })
}
async function login(entry:string){await load<{url:string}>('login',endpoint.value+'/login',response=>window.location.assign(response.url),'POST',{entry})}
async function sync(){
 await requests.run('authorization',async task=>{
  const value=await api(endpoint.value+'/authorization','POST',{},task.signal)
  task.commit(()=>{authorization.value=value})
  if(task.current())await refreshDiagnostics()
 })
}
async function subscription(){await load('subscription',endpoint.value+'/subscriptions',value=>{subscriptionResult.value=value},'POST',{operation:operation.value,id:subscriptionId.value,body:JSON.parse(subscriptionBody.value),taskId:taskId.value,attempt:attempt.value})}
async function role(){await load('authorization',endpoint.value+'/authorization',value=>{authorization.value=value},'POST',{operation:roleOperation.value,roleId:roleId.value})}
async function refreshSession(){await load('session','/session',value=>{session.value=value})}
async function refreshCommands(){if(app.value)await load('commands',endpoint.value+'/commands',value=>{commands.value=value})}
async function refreshTab(){if(tab.value==='notifications')await refreshReceipts();else if(tab.value==='commands')await refreshCommands();else if(tab.value==='login')await refreshSession()}
async function tick(){if(!poll.value||refreshing||document.hidden)return;refreshing=true;try{await refreshApps();await refreshTab()}catch(e){error.value=e instanceof Error?e.message:String(e)}finally{refreshing=false}}
watch([tab,page,allApps],()=>run(refreshTab))
watch(selected,()=>{requests.cancel();busy.value=false;error.value='';result.value=undefined;authorization.value=undefined;diagnostics.value=undefined;receipt.value=undefined;commands.value=undefined;subscriptionResult.value=undefined;session.value=undefined;run(refreshTab)},{flush:'sync'})
onMounted(()=>run(async()=>{info.value=await api<ServiceInfo>('/info');await refreshApps();await refreshReceipts();timer=setInterval(tick,4000)}))
onUnmounted(()=>{requests.cancel();if(timer)clearInterval(timer)})
</script>
<template>
 <div class="workspace">
  <header class="top"><div><strong>JetLinks SDK</strong><span class="muted">接入验证</span></div><a-tag>{{ info?.language || '连接中' }} · {{ info?.sdkVersion || '—' }}</a-tag></header>
  <div class="context"><a-select v-model:value="selected" placeholder="选择已配置或收到通知的应用" class="app-select" :options="apps.map(a=>({value:a.contextId,label:(a.platformId || a.contextId)+' / '+a.applicationId}))"/><a-tag v-if="app" :color="app.deleted?'red':app.enabled?'green':'default'">{{ app.deleted?'已删除':app.enabled?'已启用':'已停用' }}</a-tag><a-button @click="run(refreshApps)">刷新应用</a-button><a-checkbox v-model:checked="poll">自动刷新记录</a-checkbox></div>
  <div v-if="app" class="identity"><span>{{ app.type==='api'?'API 应用':'第三方业务应用' }}</span><code>{{ app.apiBaseUrl || '等待可信地址' }}</code><span>凭据版本 {{ app.keyRevision }}</span><a-tag v-if="!app.credentialReady" color="orange">等待凭据通知</a-tag></div>
  <a-alert v-if="error" type="error" :message="error" show-icon closable class="error" @close="error=''"/>
  <a-tabs v-model:active-key="tab">
   <a-tab-pane key="call" tab="平台调用">
    <a-empty v-if="!app" description="先在 Example 配置中填写 API 应用，或配置模板后接收业务应用通知"/>
    <div v-else class="split">
     <section class="section"><h2>发起请求</h2><a-form layout="vertical">
      <div class="row"><a-form-item label="认证"><a-select v-model:value="authMode" :options="[{value:'oauth2',label:'OAuth2 机器 Token'},{value:'local',label:'本地签名机器 Token'},{value:'user',label:'配置的用户 Token'}]"/></a-form-item><a-form-item label="客户端"><a-select v-model:value="client" :options="clientModes"/></a-form-item></div>
      <div class="row"><a-form-item label="方法"><a-select v-model:value="method" :options="['GET','POST','PUT','PATCH','DELETE'].map(value=>({value}))"/></a-form-item><a-form-item label="平台内路径" class="grow"><a-input v-model:value="path" aria-label="平台内路径"/></a-form-item></div>
      <a-form-item label="JSON 请求正文（可选）"><a-textarea v-model:value="body" :rows="7" aria-label="JSON 请求正文"/></a-form-item>
      <a-space><a-checkbox v-model:checked="stream">NDJSON 流</a-checkbox><a-button type="primary" :loading="busy" @click="run(invoke)">发送请求</a-button><a-button v-if="busy&&stream" @click="requests.cancel('request')">取消流</a-button><a-button @click="run(refreshDiagnostics)">查看缓存状态</a-button></a-space>
     </a-form></section>
     <div><JsonPanel :value="result ?? '发送请求后显示实际响应'" title="调用结果"/><JsonPanel v-if="diagnostics" :value="diagnostics" title="缓存与并发"/></div>
    </div>
   </a-tab-pane>
   <a-tab-pane key="notifications" tab="通知记录">
    <div class="toolbar"><a-checkbox v-model:checked="allApps">全部应用</a-checkbox><a-input v-model:value="eventType" placeholder="事件类型" aria-label="事件类型" style="width:200px"/><a-input v-model:value="eventId" placeholder="eventId" aria-label="事件 ID" style="width:200px"/><a-select v-model:value="receiptStatus" :options="[{value:'',label:'全部状态'},{value:'accepted',label:'已受理'},{value:'duplicate',label:'重复'},{value:'rejected',label:'验签失败'}]" style="width:120px"/><a-button @click="run(async()=>{page=0;await refreshReceipts()})">查询通知</a-button><span class="muted">{{ total }} 条记录</span></div>
    <div class="notifications"><section class="event-list"><a-empty v-if="!receipts.length" description="等待平台投递至 /jetlinks/events"/>
     <button v-for="item in receipts" :key="item.recordId" class="event" :class="{active:receipt?.recordId===item.recordId}" @click="run(()=>loadReceipt(item))"><div><a-tag :color="item.accepted?'green':'red'">{{ item.accepted?(item.duplicate?'重复已受理':'已受理'):'验签失败' }}</a-tag><time>{{ new Date(item.receivedAt).toLocaleTimeString() }}</time></div><strong>{{ item.type || '未可信通知' }}</strong><span>{{ item.platformId || 'API' }} / {{ item.applicationId || '未知应用' }}</span><code>{{ item.eventId || item.recordId }}</code></button>
     <a-pagination :current="page+1" :total="total" :page-size="50" :show-size-changer="false" size="small" @change="page=Math.max(0,$event-1)"/>
    </section><div><template v-if="receipt"><JsonPanel :value="receipt" title="通知详情（敏感字段已脱敏）"/><JsonPanel :value="receipt.rawBody" title="收到的正文（展示视图）"/></template><a-empty v-else description="选择记录查看正文、身份和受理结果"/></div></div>
   </a-tab-pane>
   <a-tab-pane key="login" tab="登录与授权">
    <div class="toolbar"><a-button :disabled="!app" @click="run(()=>login('third-party'))">第三方发起登录</a-button><a-button :disabled="!app||app.type!=='business'" @click="run(()=>login('platform'))">从平台进入应用</a-button><a-button @click="run(refreshSession)">刷新会话</a-button><a-button @click="run(async()=>{await api('/session','DELETE');await refreshSession()})">退出本地会话</a-button></div>
    <div class="split"><JsonPanel :value="session ?? '尚未建立本地会话'" title="当前用户"/>
     <section class="section"><h2>授权事实</h2><a-space wrap><a-button :disabled="!app" :loading="busy" @click="run(sync)">同步成员、角色和 API 授权</a-button><a-input v-model:value="roleId" placeholder="角色 ID"/><a-select v-model:value="roleOperation" :options="[{value:'roleAuthorization',label:'角色功能'},{value:'roleMenus',label:'角色菜单'},{value:'assets',label:'资产策略'}]" style="width:130px"/><a-button :disabled="!roleId||!app" @click="run(role)">查询角色</a-button></a-space><JsonPanel :value="authorization ?? '同步成功后显示完整快照；失败保留上次结果'"/></section></div>
   </a-tab-pane>
   <a-tab-pane key="subscriptions" tab="订阅管理">
    <div class="split"><section class="section"><h2>订阅与投递</h2><a-form layout="vertical">
     <a-form-item label="操作"><a-select v-model:value="operation" :options="['query','create','validate','update','get','enable','disable','delete','events','channels','tasks','records','payload'].map(value=>({value,label:value}))"/></a-form-item>
     <a-form-item label="订阅 ID"><a-input v-model:value="subscriptionId"/></a-form-item><div class="row"><a-input v-model:value="taskId" placeholder="正文查询：taskId"/><a-input-number v-model:value="attempt" :min="1"/></div>
     <a-form-item label="订阅 JSON"><a-textarea v-model:value="subscriptionBody" :rows="9"/></a-form-item><a-button type="primary" :disabled="!app" :loading="busy" @click="run(subscription)">执行订阅操作</a-button>
    </a-form><p class="muted">管理动作需要相应权限。可在服务端为 API 应用单独配置 managementToken。</p></section><JsonPanel :value="subscriptionResult ?? '执行操作后显示平台结果'"/></div>
   </a-tab-pane>
   <a-tab-pane key="commands" tab="命令服务">
    <div class="toolbar"><a-button :disabled="!app" @click="run(refreshCommands)">刷新调用记录</a-button><span class="muted">在平台模板中将 api.address 指向本服务 /api</span></div>
    <JsonPanel :value="commands ?? '等待平台调用 GetServiceHealth、GetServiceInfo、GetTenantResources、ReadTenantUsage 或 SaveTenantLimits'" title="平台真实入站命令"/>
   </a-tab-pane>
  </a-tabs>
 </div>
</template>
