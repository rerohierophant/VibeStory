package com.vibestory.agent;

/** Chooses the next step from persisted production facts assembled by the application. */
public final class EpisodePlanSkill {
    private EpisodePlanSkill() {}

    public enum Role { STORY, STORYBOARD, VIDEO }
    public enum Action {
        WRITE_STORY, BREAK_DOWN, WAIT_FOR_BREAKDOWN, CHECK_BREAKDOWN,
        CONFIRM_SHOTS, GENERATE_IMAGES, WAIT_FOR_IMAGES, CHECK_IMAGES,
        SELECT_FRAMES, GENERATE_VIDEOS, WAIT_FOR_VIDEOS, CHECK_VIDEOS, COMPLETE
    }

    public record StoryContext(boolean readyForBreakdown, String taskState) {}
    public record StoryboardContext(int shots, int unconfirmed, int missing, int active, int issues) {}
    public record VideoContext(int missing, int withoutFrame, int active, int issues) {}
    public record Next(Role role, Action action, String message) {}

    public static Next next(StoryContext story, StoryboardContext storyboard, VideoContext video) {
        if ("QUEUED".equals(story.taskState()) || "RUNNING".equals(story.taskState()))
            return new Next(Role.STORY, Action.WAIT_FOR_BREAKDOWN, "正在拆镜，完成后可确认镜头");
        if (storyboard.shots() == 0) {
            if (!story.readyForBreakdown()) return new Next(Role.STORY, Action.WRITE_STORY, "先在剧本页保存至少 20 字符的本集正文");
            if ("FAILED".equals(story.taskState()) || "UNKNOWN".equals(story.taskState()))
                return new Next(Role.STORY, Action.CHECK_BREAKDOWN, "请在剧本页处理原拆镜任务");
            return new Next(Role.STORY, Action.BREAK_DOWN, "正文已就绪，可以拆成镜头");
        }
        if (storyboard.unconfirmed() > 0)
            return new Next(Role.STORYBOARD, Action.CONFIRM_SHOTS, "请检查并确认本集镜头");
        if (storyboard.active() > 0)
            return new Next(Role.STORYBOARD, Action.WAIT_FOR_IMAGES, "已有分镜图任务，正在等待结果");
        if (storyboard.issues() > 0)
            return new Next(Role.STORYBOARD, Action.CHECK_IMAGES, "部分镜头缺图，请处理原出图任务");
        if (storyboard.missing() > 0)
            return new Next(Role.STORYBOARD, Action.GENERATE_IMAGES, "可以给已确认且缺图的镜头出图");
        if (video.active() > 0)
            return new Next(Role.VIDEO, Action.WAIT_FOR_VIDEOS, "已有视频任务，正在等待结果");
        if (video.issues() > 0)
            return new Next(Role.VIDEO, Action.CHECK_VIDEOS, "请查看未成功的视频任务");
        if (video.withoutFrame() > 0)
            return new Next(Role.VIDEO, Action.SELECT_FRAMES, "请先为缺视频的镜头选定首帧");
        if (video.missing() > 0)
            return new Next(Role.VIDEO, Action.GENERATE_VIDEOS, "请检查已选首帧，然后提交视频生成");
        return new Next(Role.VIDEO, Action.COMPLETE, "本集镜头均已有视频");
    }
}
