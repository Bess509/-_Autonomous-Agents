export type TraceStatus='running'|'finished'
export type StepTrace={id:string;name:string;status:TraceStatus}
export type ToolTrace={id:string;name:string;status:TraceStatus;result?:string}
export type RunState={
 status:'idle'|'running'|'finished'|'error'
 text:string
 thinking:string
 thinkingStatus?:TraceStatus
 steps:StepTrace[]
 tools:ToolTrace[]
 route?:string
 agents:string[]
 runId?:string
 messageId?:string
 seenEvents:string[]
 endedMessages:string[]
 error?:string
}

export const initialState:RunState={status:'idle',text:'',thinking:'',steps:[],tools:[],agents:[],seenEvents:[],endedMessages:[]}

const stableEventId=(event:any)=>String(event.eventId??`${event.runId??''}:${event.sequence??event.timestamp??''}:${event.type}:${event.messageId??event.toolCallId??event.stepName??''}`)
const traceId=(event:any,prefix:string)=>String(event.toolCallId??event.eventId??`${prefix}-${event.timestamp??''}-${event.stepName??''}`)

export function reduceEvent(state:RunState,event:any):RunState{
 const identity=stableEventId(event)
 if(state.seenEvents.includes(identity))return state
 const seen=[...state.seenEvents,identity]
 switch(event.type){
  case'RUN_STARTED':
   if(state.runId===event.runId)return{...state,seenEvents:seen}
   return{...initialState,status:'running',runId:event.runId,seenEvents:[identity]}
  case'STEP_STARTED':
   return{...state,seenEvents:seen,steps:[...state.steps,{id:traceId(event,'step'),name:event.stepName??'处理中',status:'running'}]}
  case'STEP_FINISHED':{
   const index=[...state.steps].reverse().findIndex(item=>item.name===event.stepName&&item.status==='running')
   if(index<0)return state
   const actual=state.steps.length-1-index
   return{...state,seenEvents:seen,steps:state.steps.map((item,i)=>i===actual?{...item,status:'finished'}:item)}
  }
  case'TOOL_CALL_START':
   return{...state,seenEvents:seen,tools:[...state.tools,{id:traceId(event,'tool'),name:event.toolCallName??'未命名能力',status:'running'}]}
  case'TOOL_CALL_RESULT':
   return{...state,seenEvents:seen,tools:state.tools.map(item=>item.id===String(event.toolCallId)
    ?{...item,status:'finished',result:event.content}:item)}
  case'TEXT_MESSAGE_START':
   return{...state,seenEvents:seen,messageId:event.messageId??state.messageId}
  case'TEXT_MESSAGE_CONTENT':
   if(state.endedMessages.includes(String(event.messageId)))return{...state,seenEvents:seen}
   if(state.messageId&&event.messageId&&state.messageId!==event.messageId)return{...state,seenEvents:seen}
   return{...state,seenEvents:seen,messageId:event.messageId??state.messageId,text:state.text+(event.delta??'')}
  case'THINKING_START':
   return{...state,seenEvents:seen,thinkingStatus:'running'}
  case'THINKING_CONTENT':
   return{...state,seenEvents:seen,thinkingStatus:'running',thinking:state.thinking+(event.delta??'')}
  case'THINKING_END':
   return{...state,seenEvents:seen,thinkingStatus:'finished'}
  case'TEXT_MESSAGE_END':
   return{...state,seenEvents:seen,endedMessages:[...new Set([...state.endedMessages,String(event.messageId)])]}
  case'STATE_SNAPSHOT':
   return{...state,seenEvents:seen,route:event.snapshot?.route,agents:Array.isArray(event.snapshot?.agents)?event.snapshot.agents:state.agents}
  case'RUN_FINISHED':
   return{...state,seenEvents:seen,status:'finished',steps:state.steps.map(item=>({...item,status:'finished'})),tools:state.tools.map(item=>({...item,status:'finished'}))}
  case'RUN_ERROR':
   return{...state,seenEvents:seen,status:'error',error:event.message??'运行失败'}
  default:return{...state,seenEvents:seen}
 }
}

export function parseSse(raw:string){
 return raw.split('\n\n').map(frame=>frame.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n')).filter(Boolean).map(frame=>JSON.parse(frame))
}

export async function* streamSse(response:Response){
 if(!response.body){for(const event of parseSse(await response.text()))yield event;return}
 const reader=response.body.getReader(),decoder=new TextDecoder();let buffer=''
 while(true){
  const{done,value}=await reader.read();buffer+=decoder.decode(value,{stream:!done})
  const frames=buffer.split('\n\n');buffer=frames.pop()??''
  for(const frame of frames){
   const data=frame.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n')
   if(data)yield JSON.parse(data)
  }
  if(done)break
 }
 if(buffer.trim()){const data=buffer.replace(/^data:\s*/,'');if(data)yield JSON.parse(data)}
}
