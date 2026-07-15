package com.medix.agui;

import java.util.List;
import java.util.Map;

public record RunAgentInput(String threadId,String runId,Object state,List<Message> messages,List<Object> tools,List<Object> context,Map<String,Object> forwardedProps) {
    public record Message(String id,String role,String content){}
    public String latestUserMessage(){return messages==null?null:messages.stream().filter(m->"user".equals(m.role())).reduce((a,b)->b).map(Message::content).orElse(null);}
}
