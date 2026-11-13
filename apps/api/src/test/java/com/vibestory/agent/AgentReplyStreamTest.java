package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentReplyStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void forwardsOnlyVisibleContentAndReassemblesTheReply() throws IOException {
        var deltas = new ArrayList<String>();
        var reply = read("""
            data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"内部推理"}}]}

            data: {"choices":[{"delta":{"content":"雨夜"}}]}

            data: {"choices":[{"delta":{"content":"悬疑🌧️"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"completion_tokens":4}}

            data: [DONE]
            """, deltas);
        assertThat(deltas).containsExactly("雨夜", "悬疑🌧️");
        assertThat(reply.message().path("content").asText()).isEqualTo("雨夜悬疑🌧️");
        assertThat(reply.usage().path("completion_tokens").asInt()).isEqualTo(4);
    }

    @Test void reassemblesSplitToolArgumentsWithoutSendingThemToTheBrowser() throws IOException {
        var deltas = new ArrayList<String>();
        var reply = read("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"execute_action","arguments":"{"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]
            """, deltas);
        assertThat(deltas).isEmpty();
        var call = reply.message().path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("call_1");
        assertThat(call.path("function").path("arguments").asText()).isEqualTo("{}");
    }

    @Test void anInterruptedReplyCannotBeCommittedAsComplete() {
        var deltas = new ArrayList<String>();
        assertThatThrownBy(() -> read("""
            data: {"choices":[{"delta":{"content":"尚未完成"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
            """, deltas)).isInstanceOf(IOException.class);
        assertThat(deltas).containsExactly("尚未完成");
    }

    private AgentLoopModel.Reply read(String events, ArrayList<String> deltas) throws IOException {
        try (var reader = new BufferedReader(new StringReader(events))) {
            return AgentReplyStream.readEvents(reader, json, deltas::add, System.nanoTime() + Duration.ofSeconds(5).toNanos());
        }
    }
}
