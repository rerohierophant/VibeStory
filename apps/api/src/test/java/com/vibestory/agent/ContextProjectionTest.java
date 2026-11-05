package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContextProjectionTest {
    final ObjectMapper json=new ObjectMapper();
    final ContextProjection projector=new ContextProjection(json);
    AgentContextProperties policy(int keep) { return new AgentContextProperties(true,1024,1024,64,8192,4096,keep,1,4096,2048,32); }

    @Test void oversizedResultBecomesARecoverableUnicodePreviewWithoutChangingOriginalHistory() throws Exception {
        var history=start();
        add(history,"a",List.of("call-a"),"沈夜😀".repeat(1000),true);
        String original=history.toString();
        var projected=projector.offload(UUID.randomUUID(),history,policy(1));
        assertThat(history.toString()).isEqualTo(original);
        var result=projector.result(projected.messages().get(3));
        assertThat(result.path("offloaded").asBoolean()).isTrue();
        assertThat(result.path("reference").path("callId").asText()).isEqualTo("call-a");
        assertThat(result.path("reference").path("sha256").asText()).isEqualTo(ContextProjection.hash(history.get(3).path("content").asText()));
        assertThat(result.path("preview").asText().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
        assertThat(projected.messages().get(2)).isEqualTo(history.get(2));
    }

    @Test void combinedNewBatchUsesItsOwnBudgetAndRetainsEveryCallId() throws Exception {
        var history=start();
        add(history,"batch",List.of("a","b","c"),"x".repeat(800),true);
        var result=projector.offload(UUID.randomUUID(),history,policy(1));
        assertThat(result.changes()).hasSize(3);
        assertThat(result.messages()).hasSize(history.size());
        for(int i=3;i<6;i++) assertThat(result.messages().get(i).path("tool_call_id")).isEqualTo(history.get(i).path("tool_call_id"));
    }

    @Test void microOnlyClearsConsumedOldBodiesAndPreservesErrorsRecentAndUnseenBatch() throws Exception {
        var history=start();
        add(history,"error",List.of("error"),"old failure",false);
        add(history,"old",List.of("old"),"old-body".repeat(900),true);
        add(history,"recent",List.of("recent"),"recent-body".repeat(900),true);
        add(history,"unseen",List.of("new-a","new-b"),"unseen-body".repeat(900),true);
        var result=projector.micro(UUID.randomUUID(),history,policy(1),v -> v.toString().getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.changes()).extracting(ContextProjection.Change::callId).containsExactly("old");
        assertThat(result.messages().size()).isEqualTo(history.size());
        for(int i=0;i<history.size();i++) if(i!=5) assertThat(result.messages().get(i)).isEqualTo(history.get(i));
        assertThat(projector.result(result.messages().get(5)).path("olderResult").asBoolean()).isTrue();
    }

    @Test void summaryBoundaryKeepsCurrentRequestAndAnEntireMultiCallGroup() throws Exception {
        var history=start();
        add(history,"older",List.of("a","b"),"old",true);
        add(history,"latest",List.of("c","d"),"new",true);
        int cut=projector.summaryCut(history,1);
        assertThat(cut).isEqualTo(5);
        var folded=projector.fold(history,1,cut,json.valueToTree(Map.of("summary","summary")),json.createObjectNode(),history.size(),"hash");
        assertThat(folded.get(2)).isEqualTo(history.get(1));
        assertThat(folded.get(3)).isEqualTo(history.get(5));
        assertThat(folded.get(4).path("tool_call_id").asText()).isEqualTo("c");
        assertThat(folded.get(5).path("tool_call_id").asText()).isEqualTo("d");
        history.remove(history.size()-1);
        assertThat(projector.summaryCut(history,1)).isEqualTo(1);
    }

    @Test void chunkingCoversEveryOriginalCharacterIncludingSupplementaryUnicode() {
        String original="start"+"😀沈夜".repeat(1000)+"middle-critical-fact"+"雨夜😀".repeat(1000)+"end";
        var parts=ContextProjection.chunks(original,1024);
        assertThat(String.join("",parts)).isEqualTo(original);
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(part.charAt(part.length()-1))).isFalse();
        });
    }

    private ArrayNode start() {
        return json.createArrayNode().add(json.valueToTree(Map.of("role","system","content","original system")))
            .add(json.valueToTree(Map.of("role","user","content","当前请求：保留首帧，不要生成视频")));
    }
    private void add(ArrayNode history,String label,List<String> ids,String body,boolean ok) throws Exception {
        history.add(json.valueToTree(Map.of("role","assistant","content",label,"tool_calls",ids.stream().map(id -> Map.of(
            "id",id,"type","function","function",Map.of("name","get_status","arguments","{}"))).toList())));
        for(String id:ids) {
            JsonNode result=json.valueToTree(ok ? Map.of("ok",true,"tool","get_status","data",Map.of("text",body))
                : Map.of("ok",false,"tool","get_status","error",Map.of("message",body)));
            history.add(json.valueToTree(Map.of("role","tool","tool_call_id",id,"content",json.writeValueAsString(result))));
        }
    }
}
