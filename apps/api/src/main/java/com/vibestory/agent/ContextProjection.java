package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Lossless audit messages never pass through a mutating projection. */
final class ContextProjection {
    record Change(String callId, String layer, int beforeBytes, int afterBytes, String hash) {}
    record Projection(ArrayNode messages, List<Change> changes) {}
    private static final Set<String> ANCHORS = Set.of("id","sessionId","scriptId","previousId","projectId","episodeId",
        "taskId","batchId","state","role","summary","message","version","location","storyRevision","shotRevision","callId");
    private final ObjectMapper json;
    ContextProjection(ObjectMapper json) { this.json=json; }

    Projection offload(UUID run, ArrayNode original, AgentContextProperties policy) {
        ArrayNode view=original.deepCopy();
        var changes=new ArrayList<Change>();
        var candidates=new ArrayList<Integer>();
        int newest=lastAssistant(view);
        for(int i=0;i<view.size();i++) if(isTool(view.get(i))) {
            var result=result(view.get(i));
            if (!result.path("tool").asText().equals("read_tool_result") && !result.path("tool").asText().equals("read_history")) {
                if (bytes(view.get(i).path("content").asText()) > policy.toolResultBytes())
                    replace(run,view,i,"OFFLOAD",policy.previewBytes(),changes);
                else if(i>newest) candidates.add(i);
            }
        }
        candidates.sort(Comparator.comparingInt((Integer i)->bytes(view.get(i).path("content").asText())).reversed());
        int batch=0;
        for(int i=Math.max(0,newest+1);i<view.size();i++) if(isTool(view.get(i))) batch+=bytes(view.get(i).path("content").asText());
        for(int i:candidates) {
            if(batch<=policy.toolBatchBytes()) break;
            int before=bytes(view.get(i).path("content").asText());
            replace(run,view,i,"OFFLOAD",policy.previewBytes(),changes);
            batch-=Math.max(0,before-bytes(view.get(i).path("content").asText()));
        }
        return new Projection(view,List.copyOf(changes));
    }

    Projection micro(UUID run, ArrayNode original, AgentContextProperties policy, java.util.function.ToIntFunction<ArrayNode> requestBytes) {
        ArrayNode view=original.deepCopy();
        var changes=new ArrayList<Change>();
        int assistant=lastAssistant(view);
        var consumed=new ArrayList<Integer>();
        for(int i=0;i<assistant;i++) if(isTool(view.get(i))) consumed.add(i);
        int eligible=Math.max(0,consumed.size()-policy.keepRecentResults());
        for(int n=0;n<eligible && requestBytes.applyAsInt(view)>policy.targetBytes();n++) {
            int i=consumed.get(n);
            // Errors are task state, not disposable output.
            if(!result(view.get(i)).path("ok").asBoolean()) continue;
            replace(run,view,i,"MICRO",0,changes);
        }
        return new Projection(view,List.copyOf(changes));
    }

    private void replace(UUID run,ArrayNode messages,int i,String layer,int preview,List<Change> changes) {
        var message=(ObjectNode)messages.get(i);
        String full=message.path("content").asText();
        var value=result(message);
        String id=message.path("tool_call_id").asText();
        var ref=value.path("reference");
        boolean wasOffloaded=value.path("offloaded").asBoolean();
        var replacement=brief(value);
        replacement.put("offloaded",true).put("olderResult",layer.equals("MICRO"));
        if(wasOffloaded) replacement.set("reference",ref.deepCopy());
        else replacement.set("reference",json.createObjectNode().put("runId",run.toString()).put("callId",id)
            .put("bytes",bytes(full)).put("sha256",hash(full)).put("readTool","read_tool_result"));
        if(preview>0) replacement.put("preview",utf8Prefix(wasOffloaded ? value.path("preview").asText() : full,preview));
        String encoded=encode(replacement);
        if(bytes(encoded)>=bytes(full)) return;
        message.put("content",encoded);
        changes.add(new Change(id,layer,bytes(full),bytes(encoded),replacement.path("reference").path("sha256").asText()));
    }

    ObjectNode brief(JsonNode result) {
        var node=json.createObjectNode().put("ok",result.path("ok").asBoolean()).put("tool",result.path("tool").asText());
        if(result.has("error")) node.set("error",result.get("error").deepCopy());
        if(result.path("data").isObject()) {
            var data=node.putObject("data");
            result.path("data").fields().forEachRemaining(field -> {
                var value=field.getValue();
                if(ANCHORS.contains(field.getKey()) && value.isValueNode() && value.asText().length()<=500) data.set(field.getKey(),value.deepCopy());
            });
        }
        if(result.has("reference")) node.set("reference",result.get("reference").deepCopy());
        return node;
    }

    /** Cut only before a closed recent assistant group; never split a multi-call batch. */
    int summaryCut(ArrayNode messages,int keepGroups) {
        var starts=new ArrayList<Integer>();
        var outstanding=new HashSet<String>();
        for(int i=1;i<messages.size();i++) {
            var message=messages.get(i);
            String role=message.path("role").asText();
            if((role.equals("assistant") || role.equals("user")) && outstanding.isEmpty()) starts.add(i);
            if(role.equals("assistant")) message.path("tool_calls").forEach(call -> outstanding.add(call.path("id").asText()));
            if(role.equals("tool")) outstanding.remove(message.path("tool_call_id").asText());
        }
        if(!outstanding.isEmpty() || starts.size()<=keepGroups) return 1;
        return starts.get(starts.size()-keepGroups);
    }

    ArrayNode fold(ArrayNode canonical,int currentRequest,int cut,JsonNode summary,JsonNode state,int sourceMessages,String sourceHash) {
        var view=json.createArrayNode();
        view.add(canonical.get(0).deepCopy());
        var memo=json.createObjectNode().put("kind","context_summary").put("referenceOnly",true);
        memo.set("summary",summary.deepCopy()); memo.set("taskState",state.deepCopy());
        memo.set("archive",json.createObjectNode().put("through",sourceMessages).put("sha256",sourceHash).put("readTool","read_history"));
        view.add(json.createObjectNode().put("role","assistant").put("content",encode(memo)));
        if(currentRequest<cut) view.add(canonical.get(currentRequest).deepCopy());
        for(int i=cut;i<canonical.size();i++) view.add(canonical.get(i).deepCopy());
        return view;
    }

    JsonNode result(JsonNode message) {
        try { return json.readTree(message.path("content").asText()); }
        catch(java.io.IOException invalid) { throw new IllegalArgumentException("工具回执无效",invalid); }
    }
    private boolean isTool(JsonNode message) { return message.path("role").asText().equals("tool"); }
    private int lastAssistant(ArrayNode messages) {
        for(int i=messages.size()-1;i>=0;i--) if(messages.get(i).path("role").asText().equals("assistant")) return i;
        return -1;
    }
    int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String utf8Prefix(String value,int maxBytes) {
        int end=0,used=0;
        while(end<value.length()) {
            int cp=value.codePointAt(end),n=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
            if(used+n>maxBytes) break;
            used+=n;end+=Character.charCount(cp);
        }
        return value.substring(0,end);
    }
    static List<String> chunks(String text,int maxBytes) {
        var chunks=new ArrayList<String>();
        for(int start=0;start<text.length();) {
            String chunk=utf8Prefix(text.substring(start),maxBytes);
            if(chunk.isEmpty()) throw new IllegalArgumentException("分块预算不足");
            chunks.add(chunk);start+=chunk.length();
        }
        return chunks;
    }
}
