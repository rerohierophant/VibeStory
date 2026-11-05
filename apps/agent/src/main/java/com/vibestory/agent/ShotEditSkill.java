package com.vibestory.agent;

import java.util.regex.Pattern;

/** The local Mock skill proposes edits. The caller resolves scope and owns every write. */
public final class ShotEditSkill {
    private static final Pattern TARGET = Pattern.compile("^\\s*(?:请\\s*)?(?:把\\s*)?第\\s*([0-9]{1,9})\\s*镜(?:头)?\\s*(.+)$");
    private static final Pattern SIZE = Pattern.compile("(?:(?:的)?景别\\s*)?(?:改成|改为|调整为|换成|设为)\\s*(远景|全景|中景|近景|特写)");
    private static final Pattern DIALOGUE = Pattern.compile("(?:把\\s*)?台词\\s*(?:短一点|短一些|简短一点|缩短(?:一点)?|改短(?:一点)?)");
    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[。！？!?])\\s*\\S", Pattern.DOTALL);
    private static final Pattern FILLER = Pattern.compile("^(?:我想|我觉得|说实话[，,]?|其实[，,]?)\\s*");

    public record Intent(int shotNumber, String shotSize, boolean shortenDialogue) {}

    public Intent parse(String instruction) {
        var target = TARGET.matcher(instruction);
        if (!target.matches() || Integer.parseInt(target.group(1)) == 0)
            throw new IllegalArgumentException("请指定一镜，例如「把第 3 镜改成近景，台词短一点」");
        String shotSize = null;
        boolean shortenDialogue = false;
        String actions = target.group(2).strip().replaceFirst("[。！!]+$", "");
        for (String action : actions.split("[，,、；;]", -1)) {
            String clause = action.strip();
            var size = SIZE.matcher(clause);
            if (size.matches() && shotSize == null) {
                shotSize = size.group(1);
            } else if (DIALOGUE.matcher(clause).matches() && !shortenDialogue) {
                shortenDialogue = true;
            } else {
                throw new IllegalArgumentException("一次可修改指定镜头的景别、缩短台词，或同时执行两项");
            }
        }
        return new Intent(Integer.parseInt(target.group(1)), shotSize, shortenDialogue);
    }

    public String shorten(String dialogue) {
        String original = dialogue.strip();
        var firstSentence = FIRST_SENTENCE.matcher(original);
        String shortened = firstSentence.find() ? firstSentence.group(1) : original;
        shortened = FILLER.matcher(shortened).replaceFirst("").strip();
        if (shortened.isBlank() || shortened.equals(original))
            throw new IllegalArgumentException("这条台词没有可缩短的片段，请在镜头编辑器里修改");
        return shortened;
    }
}
