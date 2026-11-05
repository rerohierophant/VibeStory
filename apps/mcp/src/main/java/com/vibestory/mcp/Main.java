package com.vibestory.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            var json = new ObjectMapper();
            var env = System.getenv();
            var api = new WorkspaceApi(json, env.getOrDefault("VIBESTORY_MCP_URL", "http://127.0.0.1:8080"),
                env.get("VIBESTORY_MCP_EMAIL"), env.get("VIBESTORY_MCP_PASSWORD"));
            var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
            McpServer.sync(transport)
                .serverInfo("vibestory", "0.1.0")
                .instructions("""
                    在 VibeStory 中创建和编辑分镜。先用 list_projects 找到分集 ID，再提交拆镜。生成是异步的，使用返回的任务或批次 ID 查询进度。
                    改镜先调用 preview_shot_edit，展示差异并等用户确认，再用同一分集 ID 和提案 ID 调用 apply_shot_edit。
                    确认整集镜头或提交出图前，请核对用户授权的范围。对同一改镜或出图请求重试时复用原 request_key。
                    """)
                .tools(new StoryboardTools(json, api).tools())
                .build();
        } catch (IllegalArgumentException error) {
            System.err.println("VibeStory MCP 配置无效。请检查本机 API 地址与启动参数。");
            System.exit(1);
        }
    }
}
