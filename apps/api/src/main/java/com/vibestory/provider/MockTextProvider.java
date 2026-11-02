package com.vibestory.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MockTextProvider implements TextProvider {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private MockSubmissionProbe probe;
    private static final String JS_SPACE = "[\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
    private static final Pattern LEAD_IN = Pattern.compile("(说|问|道|喊|答|嘟囔|开口)$");
    private static final Pattern QUOTE = Pattern.compile("「([^」]+)」");
    private static final Pattern QUOTED = Pattern.compile("「[^」]*」");
    private static final List<SceneOption> FALLBACK = List.of(
        new SceneOption("雨夜街道", List.of("街道", "路口", "路灯", "雨", "积水", "伞", "门口")),
        new SceneOption("便利店", List.of("便利店", "货架", "关东煮", "店员", "纸碗", "自动门", "牛奶")),
        new SceneOption("旧宅客厅", List.of("客厅", "沙发", "茶几", "继母", "院子", "桂花", "单据")));
    private static final List<String> MOVES = List.of("固定", "推", "拉", "移");
    @Override public Capabilities capabilities() { return new Capabilities(false, true); }

    @Override public Result generate(Input input) throws InterruptedException {
        com.vibestory.admission.SubmissionBoundary.beforeSubmission();
        if(probe!=null) probe.entered("BREAKDOWN");
        List<SceneOption> scenes = input.sceneOptions().isEmpty() ? FALLBACK : input.sceneOptions().stream().map(scene -> {
            if (scene.keywords() != null) return scene;
            var keywords = new ArrayList<String>();
            keywords.add(scene.name());
            for (String word : scene.name().split("[的，、\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+"))
                if (word.length() >= 2) keywords.add(word);
            return new SceneOption(scene.name(), keywords);
        }).toList();
        var drafts = new ArrayList<ShotDraft>();
        String lastScene = "";
        for (String raw : input.story().split("\\n+")) {
            String paragraph = trim(raw);
            if (paragraph.isEmpty() || paragraph.matches("^[一二三四五六七八九十]+、[\\s\\S]*")) continue;
            var sentences = merge(paragraph);
            var chunks = new ArrayList<String>();
            String current = "";
            for (String sentence : sentences) {
                if (!current.isEmpty() && !LEAD_IN.matcher(current).find() && current.length() + sentence.length() > 48) {
                    chunks.add(current); current = sentence;
                } else current += sentence;
            }
            if (!current.isEmpty()) chunks.add(current);
            for (String chunk : chunks) {
                var quote = QUOTE.matcher(chunk);
                String dialogue = quote.find() ? quote.group(1) : null;
                String rest = trim(QUOTED.matcher(chunk).replaceAll(""));
                String core = rest.replaceFirst("[。！？：:，,、]$", "");
                boolean bare = !core.isEmpty() && core.length() <= 8 && !Pattern.compile("[。！？]").matcher(core).find() && LEAD_IN.matcher(core).find();
                String description = bare ? "" : rest.replaceFirst("[，、：:]$", "");
                if (description.isEmpty()) description = dialogue != null ? "人物近景，说出台词" : chunk;
                int index = drafts.size();
                String sceneName = lastScene.isEmpty() ? scenes.getFirst().name() : lastScene;
                int bestHits = 0;
                for (SceneOption scene : scenes) {
                    int hits = (int) scene.keywords().stream().filter(chunk::contains).count();
                    if (hits > bestHits) { sceneName = scene.name(); bestHits = hits; }
                }
                lastScene = sceneName;
                drafts.add(new ShotDraft(description.substring(0, Math.min(120, description.length())), dialogue == null ? "" : dialogue,
                    sceneName, dialogue != null ? "近景" : index == 0 ? "全景" : "中景", MOVES.get(index % 4),
                    dialogue != null ? 4000 : 3000 + index % 3 * 1000, List.of()));
            }
        }
        return new Result(List.copyOf(drafts), null, "mock-splitter-v1");
    }

    private List<String> merge(String paragraph) {
        var out = new ArrayList<String>();
        String carry = "";
        for (String raw : paragraph.split("(?<=[。！？])(?!」)")) {
            String text = trim(raw);
            if (text.isEmpty()) continue;
            String core = text.replaceAll("[「」。！？\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]", "");
            if (!core.isEmpty() && core.length() <= 6 && LEAD_IN.matcher(core).find() && !out.isEmpty()) {
                out.set(out.size() - 1, out.getLast() + text); continue;
            }
            if (text.matches("^「[^」]*」$") || text.contains("「") && !text.contains("」")) { carry += text; continue; }
            out.add(carry + text); carry = "";
        }
        if (!carry.isEmpty()) out.add(carry);
        return out;
    }

    private String trim(String text) { return text.replaceAll("^" + JS_SPACE + "+|" + JS_SPACE + "+$", ""); }
}
