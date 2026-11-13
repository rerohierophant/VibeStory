package com.vibestory.agent;

import com.vibestory.agent.AgentConversationController.ChatInput;
import com.vibestory.agent.WorkbenchAgent.AgentResult;
import com.vibestory.agent.WorkbenchAgentController.AgentInstruction;
import com.vibestory.common.ApiException;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkbenchCommands {
    private final WorkbenchAgent agent;
    private final AgentWorkflows workflows;
    private final EpisodeWorkflow episodeWorkflow;
    private final CreativeWorkflows creative;
    WorkbenchCommands(WorkbenchAgent agent, AgentWorkflows workflows, EpisodeWorkflow episodeWorkflow, CreativeWorkflows creative) {
        this.agent = agent;
        this.workflows = workflows;
        this.episodeWorkflow = episodeWorkflow;
        this.creative = creative;
    }
    public AgentResult run(long user, UUID conversation, ChatInput input, UUID commandKey, String instruction) {
        String command = instruction.replaceFirst("^请\\s*", "").replaceFirst("[。！!]+$", "");
        if (WorkbenchSkills.isStoryToImages(command)) {
            workflows.start(user, conversation, input.requestKey(), input.projectId(), input.episodeId());
            return AgentResult.note(WorkbenchSkills.Skill.STORY, "已登记拆镜与出图流程", List.of());
        }
        if (AgentIntentRouter.isContinue(command)) {
            var message = workflows.continueRun(user, conversation, input.projectId(), input.episodeId());
            if (message == null) {
                if (input.episodeId() == null) {
                    if (input.projectId() == null) throw ApiException.badRequest("请先打开要继续的项目或分集");
                    message = projectNext(creative.project(user, input.projectId()));
                } else {
                    var state = creative.episodeSummary(user, input.episodeId(), input.projectId());
                    message = creativeNext(state);
                    if (message == null) message = episodeWorkflow.plan(user, input.episodeId()).next().message();
                }
            }
            return AgentResult.note(WorkbenchSkills.Skill.PROGRESS, message, List.of());
        }
        if (command.matches("只重试(?:上次|上一批)?失败的(?:任务)?")) {
            var count = workflows.retryFailed(user, conversation, input.projectId(), input.episodeId());
            if (count != null) return AgentResult.note(WorkbenchSkills.Skill.PROGRESS,
                count == 0 ? "这次流程暂无可重试的已知失败任务" : "已重新排队 " + count + " 个失败任务", List.of());
        }
        return agent.run(user, new AgentInstruction(commandKey, instruction, input.projectId(), input.episodeId()));
    }

    private String projectNext(CreativeWorkflows.CreativeProject project) {
        if (project.source() == null) return "请先在项目概览导入剧本或输入故事想法";
        if (project.plan() == null || !project.plan().current()) return "原文已就绪，可说「规划项目分集」";
        var plan = project.plan();
        return switch (plan.state()) {
            case QUEUED, RUNNING -> "正在规划项目分集";
            case READY -> plan.summary() + "。请在项目概览审阅，或说「确认分集草案 " + plan.id() + "」";
            case FAILED, UNKNOWN -> plan.error();
            case APPLIED -> "分集已创建，请打开要继续创作的一集";
        };
    }

    private String creativeNext(CreativeWorkflows.CreativeEpisode episode) {
        var board = episode.storyboard();
        if (board != null && board.current()) {
            return switch (board.state()) {
                case QUEUED, RUNNING -> "分镜子 Agent 正在规划本集分镜";
                case READY -> board.summary() + "。审阅后可说「应用分镜草案 " + board.id() + "」";
                case FAILED, UNKNOWN -> board.error();
                case APPLIED -> null;
            };
        }
        var script = episode.script();
        if (script == null || !script.current() || board != null && board.state() == CreativeWorkflows.CreativeState.APPLIED) return null;
        return switch (script.state()) {
            case QUEUED, RUNNING -> "剧本子 Agent 正在整理本集正文";
            case READY -> "剧本已就绪，版本 " + script.id() + "。可说「规划本集分镜」";
            case FAILED, UNKNOWN -> script.error();
            case APPLIED -> null;
        };
    }

}
