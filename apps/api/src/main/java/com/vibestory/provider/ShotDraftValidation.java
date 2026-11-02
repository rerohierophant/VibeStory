package com.vibestory.provider;

import com.vibestory.common.TextInput;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ShotDraftValidation {
    public static final Set<String> SIZES = Set.of("远景", "全景", "中景", "近景", "特写");
    public static final Set<String> MOVES = Set.of("固定", "推", "拉", "摇", "移", "跟");

    public void validate(List<TextProvider.ShotDraft> shots) {
        if (shots == null || shots.isEmpty() || shots.size() > 300) throw new IllegalArgumentException("镜头数量须为 1 至 300");
        for (var shot : shots) {
            if (shot == null || shot.description() == null || shot.dialogue() == null || shot.sceneName() == null ||
                    shot.sceneName().isBlank() || shot.characterNames() == null || shot.characterNames().size() > 20 ||
                    shot.description().length() > 4000 || shot.dialogue().length() > 4000 ||
                    !SIZES.contains(shot.shotSize()) || !MOVES.contains(shot.cameraMove()) || shot.durationMs() < 1500 || shot.durationMs() > 12000 ||
                    shot.description().isBlank() && shot.dialogue().isBlank())
                throw new IllegalArgumentException("镜头字段、景别、运镜或时长无效");
            TextInput.validate(shot.description(), 16000, "画面描述");
            TextInput.validate(shot.dialogue(), 16000, "台词");
            TextInput.name(shot.sceneName(), "场景名称");
            shot.characterNames().forEach(name -> TextInput.name(name, "人物名称"));
        }
    }
}
