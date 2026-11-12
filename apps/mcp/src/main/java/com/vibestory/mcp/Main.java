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
                    先用 list_projects 找到分集 ID。保存用户确认的正文时，从 get_episode 获取当前 storyRevision，再调用 save_episode_story。
                    改镜先调用 preview_shot_edit，展示差异并等用户确认，再用同一分集 ID 和提案 ID 调用 apply_shot_edit。
                    用 get_shot_media 查询候选图。用户指定首帧后可调用 select_shot_image。提交视频时传入每个镜头及其经确认的首帧 ID。
                    确认整集镜头或提交生成前，请核对用户授权的范围。生成是异步的，使用任务 ID 或批次 ID 查询进度。同一改镜或批次请求重试时复用原 request_key。
                    """)
                .tools(new StoryboardTools(json, api).tools())
                .build();
        } catch (IllegalArgumentException error) {
            System.err.println("VibeStory MCP 配置无效。请检查本机 API 地址与启动参数。");
            System.exit(1);
        }
    }
}
