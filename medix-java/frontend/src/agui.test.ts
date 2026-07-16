// @vitest-environment jsdom
import React from'react'
import{describe,it,expect}from'vitest'
import{createRoot}from'react-dom/client'
import{flushSync}from'react-dom'
import{RunFinishedEventSchema,RunStartedEventSchema,TextMessageContentEventSchema}from'@ag-ui/core'
import{initialState,parseSse,reduceEvent}from'./agui'
import{AssistantAnswerText}from'./main'

describe('AG-UI reducer',()=>{
 it('streams DeepSeek thinking separately from the answer',()=>{
  let state=reduceEvent(initialState,{type:'RUN_STARTED',runId:'r',eventId:'r:1'})
  state=reduceEvent(state,{type:'THINKING_START',runId:'r',eventId:'r:2',messageId:'r:thinking'})
  state=reduceEvent(state,{type:'THINKING_CONTENT',runId:'r',eventId:'r:3',messageId:'r:thinking',delta:'分析证据'})
  state=reduceEvent(state,{type:'THINKING_END',runId:'r',eventId:'r:4',messageId:'r:thinking'})
  expect(state.thinking).toBe('分析证据')
  expect(state.thinkingStatus).toBe('finished')
  expect(state.text).toBe('')
 })
 it('streams text and ignores unknown events',()=>{
  let state=reduceEvent(initialState,{type:'RUN_STARTED'})
  state=reduceEvent(state,{type:'TEXT_MESSAGE_CONTENT',delta:'你好'})
 expect(reduceEvent(state,{type:'FUTURE_EVENT'}).text).toBe('你好')
 })
 it('records completed agent and tool traces',()=>{
  let state=reduceEvent(initialState,{type:'RUN_STARTED',timestamp:1})
  state=reduceEvent(state,{type:'STEP_STARTED',timestamp:2,stepName:'consultation_agent'})
  state=reduceEvent(state,{type:'TOOL_CALL_START',timestamp:3,toolCallId:'call-1',toolCallName:'search_knowledge'})
  state=reduceEvent(state,{type:'TOOL_CALL_RESULT',timestamp:4,toolCallId:'call-1',content:'能力调用已完成'})
  state=reduceEvent(state,{type:'STEP_FINISHED',timestamp:5,stepName:'consultation_agent'})
  expect(state.steps).toEqual([expect.objectContaining({name:'consultation_agent',status:'finished'})])
  expect(state.tools).toEqual([expect.objectContaining({name:'search_knowledge',status:'finished'})])
 })
 it('parses SSE frames',()=>expect(parseSse('data: {"type":"RUN_STARTED"}\n\n')).toHaveLength(1))
 it('matches the locked AG-UI 0.0.57 core schemas',()=>{
  expect(RunStartedEventSchema.parse({type:'RUN_STARTED',timestamp:1,threadId:'t',runId:'r'}).runId).toBe('r')
  expect(TextMessageContentEventSchema.parse({type:'TEXT_MESSAGE_CONTENT',timestamp:2,messageId:'m',delta:'ok'}).delta).toBe('ok')
  expect(RunFinishedEventSchema.parse({type:'RUN_FINISHED',timestamp:3,threadId:'t',runId:'r',outcome:{type:'success'}}).runId).toBe('r')
 })
 it('is idempotent for duplicate content, steps, tools and run lifecycle',()=>{
  const events=[
   {type:'RUN_STARTED',runId:'r',eventId:'r:1',sequence:1},
   {type:'STEP_STARTED',runId:'r',eventId:'r:2',sequence:2,stepName:'diagnostic_agent'},
   {type:'TOOL_CALL_START',runId:'r',eventId:'r:3',sequence:3,toolCallId:'call-1',toolCallName:'analyze_symptoms'},
   {type:'TEXT_MESSAGE_START',runId:'r',eventId:'r:4',sequence:4,messageId:'r:assistant'},
   {type:'TEXT_MESSAGE_CONTENT',runId:'r',eventId:'r:5',sequence:5,messageId:'r:assistant',delta:'头痛建议'},
   {type:'TEXT_MESSAGE_END',runId:'r',eventId:'r:6',sequence:6,messageId:'r:assistant'},
   {type:'RUN_FINISHED',runId:'r',eventId:'r:7',sequence:7}
  ]
  let once=initialState
  for(const event of events)once=reduceEvent(once,event)
  let duplicate=once
  for(const event of events)duplicate=reduceEvent(duplicate,event)
  expect(duplicate).toEqual(once)
  expect(duplicate.text).toBe('头痛建议')
  expect(duplicate.steps).toHaveLength(1)
  expect(duplicate.tools).toHaveLength(1)
 })
 it('keeps distinct parallel calls with different stable ids',()=>{
  let state=reduceEvent(initialState,{type:'RUN_STARTED',runId:'r',eventId:'r:1'})
  state=reduceEvent(state,{type:'TOOL_CALL_START',runId:'r',eventId:'r:2',toolCallId:'a',toolCallName:'assess_risk'})
  state=reduceEvent(state,{type:'TOOL_CALL_START',runId:'r',eventId:'r:3',toolCallId:'b',toolCallName:'assess_risk'})
  expect(state.tools.map(tool=>tool.id)).toEqual(['a','b'])
 })
 it('preserves one complete headache answer from AG-UI deltas through reducer and DOM',()=>{
  const answer='【证据摘要】轻度头痛常见诱因包括疲劳和缺水。\n\n【综合建议】先休息并补充水分；若症状加重或出现高危信号请及时就医。\n\n【免责声明】以上信息仅供学习和参考，不能替代专业医生的诊断和治疗。'
  const chunks=[answer.slice(0,37),answer.slice(37,91),answer.slice(91)]
  const events=[
   {type:'RUN_STARTED',runId:'headache-run',eventId:'headache-run:1',sequence:1},
   {type:'STEP_STARTED',runId:'headache-run',eventId:'headache-run:2',sequence:2,stepName:'diagnostic_agent'},
   {type:'TOOL_CALL_START',runId:'headache-run',eventId:'headache-run:3',sequence:3,toolCallId:'risk-1',toolCallName:'assess_risk'},
   {type:'TEXT_MESSAGE_START',runId:'headache-run',eventId:'headache-run:4',sequence:4,messageId:'headache-run:assistant'},
   ...chunks.map((delta,index)=>({type:'TEXT_MESSAGE_CONTENT',runId:'headache-run',eventId:`headache-run:${index+5}`,sequence:index+5,messageId:'headache-run:assistant',delta})),
   {type:'TEXT_MESSAGE_END',runId:'headache-run',eventId:'headache-run:8',sequence:8,messageId:'headache-run:assistant'},
   {type:'RUN_FINISHED',runId:'headache-run',eventId:'headache-run:9',sequence:9}
  ]
  let state=initialState
  for(const event of events)state=reduceEvent(state,event)
  for(const event of events)state=reduceEvent(state,event)
  expect(state.text).toBe(answer)
  expect((state.text.match(/【综合建议】/g)??[])).toHaveLength(1)
  expect((state.text.match(/【免责声明】/g)??[])).toHaveLength(1)

  const container=document.createElement('div')
  const root=createRoot(container)
  flushSync(()=>root.render(React.createElement(AssistantAnswerText,{text:state.text})))
  const rendered=container.querySelector('[data-testid="assistant-answer"]')
  expect(rendered?.textContent).toBe(answer)
  expect(rendered?.textContent?.endsWith('诊断和治疗。')).toBe(true)
  root.unmount()
 })
})
