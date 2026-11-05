package com.vibestory.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.agent-context")
public record AgentContextProperties(boolean enabled, int toolResultBytes, int toolBatchBytes, int previewBytes,
    int triggerBytes, int targetBytes, int keepRecentResults, int keepRecentGroups,
    int summaryInputBytes, int summaryOutputBytes, int maxSummaryCalls) {
    public AgentContextProperties {
        if (toolResultBytes < 256 || toolBatchBytes < toolResultBytes || previewBytes < 32 || previewBytes >= toolResultBytes
            || targetBytes < 4096 || triggerBytes < targetBytes || triggerBytes > 512 * 1024
            || keepRecentResults < 0 || keepRecentGroups < 1 || summaryInputBytes < 1024 || summaryInputBytes > 128 * 1024
            || summaryOutputBytes < 512 || summaryOutputBytes > 16 * 1024 || maxSummaryCalls < 1 || maxSummaryCalls > 64)
            throw new IllegalArgumentException("Agent 上下文预算无效");
    }
}
