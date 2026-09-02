package com.example.myhelper.novel;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 一章正文动笔前的可核对计划。
 *
 * <p>把“写什么”从自然语言临时猜测变成明确约束：正文和质检使用同一份计划，
 * 因此重写只会修复问题，不会悄悄改掉本章应完成的剧情。</p>
 */
public record NovelChapterPlan(
        int chapterNumber,
        String title,
        String objective,
        String conflict,
        String revealOrChange,
        String emotion,
        List<String> cast,
        List<String> plotThreads,
        String endHook) {

    public NovelChapterPlan {
        title = valueOr(title, "未命名章节");
        objective = valueOr(objective, "推进当前细纲，不新增与既有设定冲突的事实");
        conflict = valueOr(conflict, "围绕本章目标形成可见阻力");
        revealOrChange = valueOr(revealOrChange, "至少让人物关系、信息或局势发生一项可见变化");
        emotion = valueOr(emotion, "情绪随事件自然变化");
        cast = normalise(cast);
        plotThreads = normalise(plotThreads);
        endHook = valueOr(endHook, "以尚未解决的行动、选择或新信息收束");
    }

    /** 用于上下文和持久化的稳定文本格式。 */
    public String asPrompt() {
        return "【本章计划】\n"
                + "章节：第" + chapterNumber + "章《" + title + "》\n"
                + "目标：" + objective + "\n"
                + "冲突：" + conflict + "\n"
                + "变化/揭露：" + revealOrChange + "\n"
                + "情绪：" + emotion + "\n"
                + "出场人物：" + joinOrNone(cast) + "\n"
                + "相关情节线：" + joinOrNone(plotThreads) + "\n"
                + "章末钩子：" + endHook + "\n";
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> normalise(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static String joinOrNone(List<String> values) {
        return values.isEmpty() ? "未指定" : values.stream().collect(Collectors.joining("、"));
    }
}
