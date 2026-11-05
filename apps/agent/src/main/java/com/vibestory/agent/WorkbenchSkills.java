package com.vibestory.agent;

import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public final class WorkbenchSkills {
    private WorkbenchSkills() {}
    public static final String STORY_TO_IMAGES = "把这集的故事拆成镜头，我确认后给没图的镜头出图";

    public enum Skill {
        WORKSPACE("项目与分集"), STORY("故事与拆镜"), SHOT("镜头编辑与确认"),
        ASSET("参考素材"), GENERATE("分镜图与视频"), PROGRESS("进度与恢复");

        private final String label;
        Skill(String label) { this.label = label; }
    }

    private static final String NUMBER = "第\\s*(0*[1-9][0-9]{0,8})\\s*";
    private static final String NAME = "《([^》\\r\\n]{1,120})》";

    private static final String REVISION = "(?:[：:]\\s*([\\s\\S]+))?";
    private static final String ARTIFACT = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    public enum Action {
        PLAN_PROJECT(Skill.STORY, true, "规划项目分集", "规划项目分集" + REVISION),
        ACCEPT_EPISODE_PLAN(Skill.STORY, true, "确认分集草案（填写草案 ID）", "确认分集草案\\s+" + ARTIFACT),
        PREPARE_SCRIPT(Skill.STORY, true, "整理本集剧本", "整理本集剧本" + REVISION),
        PLAN_STORYBOARD(Skill.SHOT, true, "规划本集分镜", "规划本集分镜" + REVISION),
        APPLY_STORYBOARD(Skill.SHOT, true, "应用分镜草案（填写草案 ID）", "应用分镜草案\\s+" + ARTIFACT),
        OPEN_EPISODE(Skill.WORKSPACE, false, "打开《雨夜》的第 2 集", "打开" + NAME + "的" + NUMBER + "集"),
        NEW_EPISODE(Skill.WORKSPACE, true, "新建一集叫《追踪》", "新建一集(?:叫|，叫|名为)" + NAME),
        PROJECT_STYLE(Skill.WORKSPACE, true, "把项目风格改为写实", "(?:把)?项目风格(?:改为|设为)(.{1,120})"),
        SAVE_STORY(Skill.STORY, true, "把这段文字设为正文：沈夜走进雨巷，听见身后传来脚步声。",
            "(?:把)?这段文字(?:设为|作为)正文[：:]\\s*([\\s\\S]+)"),
        BREAKDOWN(Skill.STORY, true, "拆成镜头给我看看", "(?:把这集)?(?:拆成镜头(?:给我看看)?|拆解镜头)"),
        CONFIRM_SHOT(Skill.SHOT, true, "确认第 3 镜", "确认" + NUMBER + "镜(?:头)?"),
        CONFIRM_ALL(Skill.SHOT, true, "确认全部镜头", "确认(?:全部|所有)镜头"),
        GENERATE_ASSET(Skill.ASSET, true, "给角色《沈夜》生成一版参考图",
            "给(?:(角色|场景)" + NAME + "生成(?:一版)?参考图|([^《》\\r\\n]{1,120})生成(?:一版)?(角色|场景)图)"),
        SELECT_ASSET(Skill.ASSET, true, "把角色《沈夜》的第 2 版设为基准",
            "把(角色|场景)" + NAME + "的" + NUMBER + "版设为基准(?:图)?"),
        MISSING_IMAGES(Skill.GENERATE, true, "给已确认但没图的镜头出图", "给已确认但(?:没图|没有分镜图)的镜头出图"),
        MISSING_VIDEOS(Skill.GENERATE, true, "给本集已选首帧且缺视频的镜头生成视频",
            "给(?:本集)?已选首帧且(?:缺|没有)视频的镜头生成视频"),
        GENERATE_SHOT(Skill.GENERATE, true, "给第 3 镜生成视频", "给" + NUMBER + "镜(?:头)?生成(分镜图|视频)"),
        SELECT_FRAME(Skill.GENERATE, true, "选第 3 镜的第 2 张图做首帧，再生成视频",
            "选" + NUMBER + "镜(?:头)?的" + NUMBER + "张图(?:做|作为)首帧([，,]\\s*再生成视频)?"),
        VARIANT_CREATE(Skill.GENERATE, true, "给第 3 镜创建近景出图方案《雨巷》",
            "给" + NUMBER + "镜创建(远景|全景|中景|近景|特写)出图方案" + NAME),
        VARIANT_LIST(Skill.GENERATE, false, "查看出图方案", "查看(?:本集)?出图方案"),
        VARIANT_REVISE(Skill.GENERATE, true, "把出图方案《雨巷》的第 3 镜改为特写并重出",
            "把出图方案" + NAME + "的" + NUMBER + "镜改为(远景|全景|中景|近景|特写)并重出"),
        VARIANT_PREVIEW(Skill.GENERATE, false, "预览采纳出图方案《雨巷》的第 3 镜",
            "预览采纳出图方案" + NAME + "的" + NUMBER + "镜"),
        PROBLEMS(Skill.PROGRESS, false, "哪些镜头没成功", "哪些镜头(?:没成功|生成失败了?)"),
        VIDEO_SOURCE(Skill.PROGRESS, false, "第 3 镜视频用了哪张首帧", NUMBER + "镜(?:头)?视频用了哪张首帧"),
        RETRY_FAILED(Skill.PROGRESS, true, "只重试上次失败的", "只重试(?:上次|上一批)失败的(?:任务)?"),
        DESCRIBE_SHOT(Skill.SHOT, true, "把第 3 镜的画面描述改为：人物站在红色路灯下，并生成新候选",
            "(?:把|为)?第\\s*(0*[1-9][0-9]{0,8})\\s*镜(?:头)?(?:的)?画面描述(改为|改成|追加)[：:]\\s*([\\s\\S]+)"),
        EDIT_SHOT(Skill.SHOT, true, "把第 3 镜改成近景，台词短一点",
            "(?:把\\s*)?" + NUMBER + "镜(?:头)?\\s*(?:(?:的?景别)?\\s*(?:改成|改为|调整为|换成|设为)|(?:把\\s*)?台词).+");

        private final Skill skill;
        private final boolean writes;
        private final String example;
        private final Pattern pattern;

        Action(Skill skill, boolean writes, String example, String pattern) {
            this.skill = skill;
            this.writes = writes;
            this.example = example;
            this.pattern = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
        }

        public Skill skill() { return skill; }
        public boolean writes() { return writes; }
    }

    public record Entry(Skill key, String label, List<String> examples) {}
    public record Command(Action action, MatchResult match) {
        public String text(int group) { return match.group(group); }
        public int number(int group) { return Integer.parseInt(text(group)); }
    }

    public static List<Entry> catalog() {
        return Arrays.stream(Skill.values()).map(skill -> new Entry(skill, skill.label,
            java.util.stream.Stream.concat(Arrays.stream(Action.values()).filter(action -> action.skill == skill).map(action -> action.example),
                skill == Skill.STORY ? java.util.stream.Stream.of(STORY_TO_IMAGES) : java.util.stream.Stream.empty()).toList())).toList();
    }

    public static Command route(String instruction) {
        String text = instruction.strip().replaceFirst("^请\\s*", "");
        String withoutPunctuation = text.replaceFirst("[。！？!?]+$", "");
        for (Action action : Action.values()) {
            var match = action.pattern.matcher(action == Action.SAVE_STORY || action == Action.DESCRIBE_SHOT ? text : withoutPunctuation);
            if (match.matches()) return new Command(action, match.toMatchResult());
        }
        throw new IllegalArgumentException("暂时无法识别这条指令。可以说清楚要操作的对象和想做的事，例如「把第 3 镜改成近景」。");
    }
}
