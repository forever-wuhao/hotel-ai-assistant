<template>
  <main class="assistant-page">
    <header class="topbar">
      <div><div class="eyebrow">YISU · HOTEL CONCIERGE</div><h1>住得合适，问得明白</h1><p>查酒店、核对房态与入住政策，每条建议都能查看资料来源。</p></div>
      <button class="secondary" @click="reset" :disabled="busy">开始新咨询</button>
    </header>
    <div class="mode-note" v-if="status?.dataKind === 'demo'">当前使用虚构演示酒店资料。{{ status.mode === 'demo' ? '回答由离线演示流程生成，未调用真实大模型。' : '已连接大模型，酒店资料仍为演示数据。' }}</div>
    <div class="workspace">
      <aside class="guide">
        <span class="eyebrow">从你的计划开始</span><h2>先说需求，<br>再核对细节。</h2>
        <p>提供城市、每晚预算及入住日期，有助于查到符合条件的酒店。</p>
        <button v-for="prompt in examples" :key="prompt" class="example" @click="draft = prompt" :disabled="busy">{{ prompt }} <span>↗</span></button>
        <div class="boundary"><strong>咨询范围</strong><p>这里提供酒店资料与库存快照。预订、付款、取消和退款需通过酒店正式渠道办理。</p></div>
        <a href="/login">进入酒店管理 →</a>
      </aside>
      <section class="conversation" aria-label="酒店咨询">
        <div class="messages" ref="messagesElement" aria-live="polite">
          <div v-if="!messages.length" class="welcome"><span class="spark">✦</span><h2>你好，想去哪里住？</h2><p>例如“南京，每晚预算400元”。我会先查询资料，再帮你核对条件。</p></div>
          <article v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
            <div class="speaker">{{ message.role === 'user' ? '你的需求' : '酒店助手' }}</div>
            <div class="message-text">{{ message.text }}</div>
            <div v-if="message.response" class="evidence">
              <div class="steps"><span v-for="(tool,i) in message.response.tools" :key="i">{{ tool.success ? '✓' : '·' }} {{ toolNames[tool.name] || '资料查询' }}{{ tool.success ? '' : '：暂未核实' }}</span></div>
              <details v-for="source in message.response.citations" :key="source.id"><summary>{{ source.title }}</summary><p>{{ source.excerpt }}</p><small>来源：{{ source.source }}</small></details>
              <small class="timing">本轮查询用时 {{ (message.response.elapsedMs / 1000).toFixed(1) }} 秒</small>
            </div>
          </article>
          <p v-if="busy" class="loading" role="status">正在查询酒店资料，请稍候…</p>
        </div>
        <div v-if="error" class="error-state" role="alert">
          <span>{{ error }}</span>
          <button v-if="retryable" @click="send(true)" :disabled="busy">重试本条</button>
          <button v-if="expired" @click="reset">开始新咨询</button>
        </div>
        <form class="composer" @submit.prevent="send(false)">
          <textarea v-model="draft" :disabled="busy || expired" maxlength="2000" rows="3" aria-label="咨询内容" placeholder="输入城市、预算或想了解的问题…" @keydown.ctrl.enter.prevent="send(false)"></textarea>
          <div class="composer-footer"><span>Ctrl + Enter 发送 · 不自动执行订单操作</span><button class="primary" :disabled="busy || expired || !draft.trim() || status?.enabled === false">{{ busy ? '查询中' : '发送咨询 ↗' }}</button></div>
        </form>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
type Evidence = { id:string; title:string; source:string; excerpt:string; dataKind:string }
type Answer = { sessionId:string; answer:string; citations:Evidence[]; tools:{name:string;success:boolean}[]; elapsedMs:number }
type Message = {role:'user'|'assistant'; text:string; response?:Answer}
const status=ref<{mode:string;dataKind:string;enabled:boolean}>()
const draft=ref(''), error=ref(''), busy=ref(false), retryable=ref(false), expired=ref(false)
const messages=ref<Message[]>([]), messagesElement=ref<HTMLElement>()
const sessionId=ref('')
try {
  sessionId.value=sessionStorage.getItem('hotel-chat-session') || ''
  const saved=JSON.parse(sessionStorage.getItem('hotel-chat-messages') || '[]')
  if(sessionId.value && Array.isArray(saved) && saved.every(m=>(m.role==='user'||m.role==='assistant')&&typeof m.text==='string'))messages.value=saved.slice(-12)
} catch { sessionId.value='' }
let pending:{message:string;requestId:string}|null=null
const apiBase=import.meta.env.VITE_API_BASE_URL || ''
const toolNames:Record<string,string>={searchHotels:'查找酒店',hotelDetails:'核对酒店资料',roomAvailability:'核对日期房态',searchPolicies:'查阅酒店政策',walkingRoute:'查询步行路线'}
const tomorrow=new Date(Date.now()+86400000).toLocaleDateString('en-CA')
const checkout=new Date(Date.now()+3*86400000).toLocaleDateString('en-CA')
const examples=['推荐南京酒店，每晚预算400元','酒店1的取消政策是什么？',`查询酒店1在${tomorrow}至${checkout}的房态`,'酒店1到南京站的路线']
const safeErrors:Record<string,string>={SESSION_EXPIRED:'会话已过期，请开始新咨询；上一条内容不会自动重发。',CONVERSATION_BUSY:'上一条咨询仍在处理中，请稍后重试。',AI_BUSY:'当前咨询较多，请稍后重试。',MODEL_UNAVAILABLE:'模型服务暂时不可用，请稍后重试。',INVALID_ANSWER:'答复未通过资料校验，请重试或联系酒店核实。',AI_DISABLED:'咨询服务尚未配置，请联系管理员。',TURN_TIMEOUT:'查询耗时较长，请稍后重试。',ITERATION_LIMIT:'这次问题需要较多步骤，请拆成更具体的问题。',INVALID_INPUT:'请输入有效的咨询内容，最多2000字。'}
onMounted(async()=>{
  try { const response=await fetch(`${apiBase}/api/ai/agent/status`); if(!response.ok)throw new Error(); status.value=await response.json() }
  catch { error.value='暂时无法连接咨询服务，请确认服务启动后刷新页面。' }
})
function persist(){try{sessionStorage.setItem('hotel-chat-session',sessionId.value);sessionStorage.setItem('hotel-chat-messages',JSON.stringify(messages.value.slice(-12)))}catch{/* Conversation still works without browser storage. */}}
function reset(){ sessionId.value='';messages.value=[];draft.value='';persist();error.value='';expired.value=false;retryable.value=false;pending=null }
async function send(retry:boolean){
  if(busy.value || expired.value || status.value?.enabled===false)return
  if(!retry){
    if(!draft.value.trim())return
    pending={message:draft.value.trim(),requestId:crypto.randomUUID()}; messages.value.push({role:'user',text:pending.message});draft.value=''
  }
  if(!pending)return
  busy.value=true;error.value='';retryable.value=false;expired.value=false
  const abort=new AbortController(); const timeout=setTimeout(()=>abort.abort(),150000)
  try {
    // Public read-only consultation: do not send admin tokens from stale localStorage.
    const response=await fetch(`${apiBase}/api/ai/agent/chat`,{method:'POST',headers:{'Content-Type':'application/json'},signal:abort.signal,
      body:JSON.stringify({...pending,sessionId:sessionId.value||null})})
    const body=await response.json()
    if(!response.ok){
      expired.value=body.code==='SESSION_EXPIRED'; retryable.value=!expired.value && response.status!==400
      error.value=safeErrors[body.code] || '咨询暂时失败，请稍后重试。'
      return
    }
    if(typeof body.sessionId!=='string'||typeof body.answer!=='string'||!Array.isArray(body.citations)||!Array.isArray(body.tools))throw new Error('invalid response')
    sessionId.value=body.sessionId
    messages.value.push({role:'assistant',text:body.answer,response:body});persist();pending=null
  } catch { error.value='连接中断或等待超时，请重试本条咨询。';retryable.value=true }
  finally {clearTimeout(timeout);busy.value=false;await nextTick();messagesElement.value?.scrollTo({top:messagesElement.value.scrollHeight,behavior:'smooth'})}
}
</script>

<style scoped>
.assistant-page{min-height:100vh;background:#f6f4ee;color:#213b38;padding:42px max(24px,calc((100vw - 1240px)/2));font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif;box-sizing:border-box}.topbar{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:24px}.eyebrow{font-size:11px;letter-spacing:2px;font-weight:700;color:#6a8279}h1{font-size:32px;letter-spacing:-1px;margin:12px 0}p{color:#667870;font-size:14px;line-height:1.8;margin:10px 0}button{font:inherit;cursor:pointer;border:0}button:disabled{opacity:.5;cursor:not-allowed}.secondary{background:transparent;border:1px solid #ccd5ca;padding:12px 18px;border-radius:10px;color:#365148}.mode-note{background:#eee8d9;padding:11px 16px;border-radius:8px;font-size:12px;color:#706146;margin-bottom:20px}.workspace{display:grid;grid-template-columns:290px minmax(0,1fr);gap:32px}.guide{padding:24px 6px}.guide h2{font-size:27px;font-weight:500;line-height:1.5}.example{display:flex;justify-content:space-between;gap:14px;text-align:left;width:100%;background:#fffdf7;border:1px solid #e2e6dc;border-radius:10px;padding:15px;margin:10px 0;font-size:13px;color:#304e45}.example:hover{border-color:#76988a}.boundary{border-top:1px solid #dbe0d6;margin-top:28px;padding-top:24px;font-size:13px}.guide a{display:block;margin-top:28px;color:#537c6d;font-size:13px}.conversation{background:white;border:1px solid #e0e5dc;border-radius:20px;overflow:hidden;box-shadow:0 8px 36px #254c3f08}.messages{height:510px;overflow-y:auto;padding:28px}.welcome{text-align:center;padding:100px 24px}.spark{display:inline-flex;align-items:center;justify-content:center;border-radius:20px;background:#e8f0e8;color:#46705f;font-size:32px;width:64px;height:64px}.welcome h2{font-size:23px;font-weight:500}.message{border-radius:12px;padding:18px 20px;margin-bottom:18px;font-size:14px;line-height:1.8}.user{background:#f0f4ec;margin-left:36px}.assistant{background:#fbfcf9;border:1px solid #e9eee4;margin-right:24px}.speaker{font-size:11px;letter-spacing:1px;color:#718476;margin-bottom:6px}.message-text{white-space:pre-wrap;overflow-wrap:anywhere}.steps{display:flex;gap:8px;flex-wrap:wrap;margin:15px 0 10px}.steps span{font-size:11px;color:#5f796a;background:#edf3eb;border-radius:6px;padding:3px 7px}details{padding:10px 0;border-top:1px solid #e1e8dd;font-size:12px}summary{cursor:pointer;color:#4b6f5d}details p{white-space:pre-wrap;font-size:12px}small{color:#7b897f;font-size:11px}.timing{display:block;margin-top:10px}.composer{border-top:1px solid #e6eade;padding:20px}textarea{width:100%;box-sizing:border-box;border:0;outline:0;resize:vertical;font:inherit;line-height:1.7;color:#263f35}.composer-footer{display:flex;justify-content:space-between;align-items:center;gap:12px}.composer-footer span{color:#96a194;font-size:11px}.primary{background:#305c49;color:white;border-radius:9px;padding:12px 22px}.error-state{padding:14px 20px;background:#fff3e9;color:#8e582c;font-size:13px;display:flex;gap:12px;align-items:center}.error-state button{background:transparent;color:#93582c;text-decoration:underline;white-space:nowrap}.loading{font-size:13px;color:#648575}@media(max-width:760px){.assistant-page{padding:20px 14px}.topbar{align-items:start}h1{font-size:24px}.workspace{display:block}.guide{display:none}.messages{height:55vh;padding:16px}.welcome{padding:60px 10px}.message{margin-left:0;margin-right:0}.composer-footer span{max-width:150px}.secondary{white-space:nowrap;font-size:12px}}
</style>
