package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@GeneratedTool
public class TextGenerationTool {

    private final Random random = new Random();

    @Tool(description = "根据主题和体裁生成一个故事的开头段落")
    public String generateStoryStart(
            @ToolParam(description = "故事主题，例如冒险、爱情、科幻") String genre,
            @ToolParam(description = "主角名称或描述") String protagonist) {
        try {
            List<String> templates = new ArrayList<>();
            templates.add(protagonist + " was never one to shy away from a challenge, but when the " + genre + " began, everything changed.");
            templates.add("In a world where " + genre + " was just a myth, " + protagonist + " stumbled upon a secret that would rewrite history.");
            templates.add(protagonist + " had always dreamed of a life filled with " + genre + ", but reality turned out to be far stranger.");
            templates.add("The day started like any other for " + protagonist + ", until the unmistakable signs of " + genre + " appeared.");
            templates.add("When " + protagonist + " woke up that morning, they had no idea they were about to become the central figure in a " + genre + " tale.");
            return templates.get(random.nextInt(templates.size()));
        } catch (Exception e) {
            return "抱歉，生成故事开头时发生错误：" + e.getMessage();
        }
    }

    @Tool(description = "根据主题生成一个简单的文章提纲，指定小节数量")
    public String generateArticleOutline(
            @ToolParam(description = "文章主题") String topic,
            @ToolParam(description = "提纲小节数，默认3") int sections) {
        try {
            if (sections <= 0) sections = 3;
            StringBuilder outline = new StringBuilder("文章提纲：《" + topic + "》\n");
            for (int i = 1; i <= sections; i++) {
                outline.append(i).append(". ").append("关于").append(topic).append("的第").append(i).append("个方面\n");
            }
            return outline.toString();
        } catch (Exception e) {
            return "抱歉，生成提纲时发生错误：" + e.getMessage();
        }
    }

    @Tool(description = "基于给定的前文继续写作，补充下一段落")
    public String continueWriting(
            @ToolParam(description = "已有的前文内容") String previousText,
            @ToolParam(description = "写作风格，例如幽默、严肃、科幻") String style) {
        try {
            List<String> continuations = new ArrayList<>();
            continuations.add("紧接着，事情的发展出乎所有人的意料。谁也没有想到，" + style + "的元素会以这种方式渗透进每一个细节。");
            continuations.add("然而，这并不是故事的终点。在" + style + "的氛围中，新的线索逐渐浮出水面，指向一个更大的谜团。");
            continuations.add("伴随着" + style + "特有的张力，场景切换到了一个全新的地点，那里隐藏着改变一切的关键信息。");
            continuations.add("尽管每个人物的动机依然模糊，但" + style + "的底色让他们的行动变得更加耐人寻味。");
            continuations.add("在这一刻，" + style + "与现实的界限变得模糊，仿佛所有的叙事规则都在为即将到来的高潮重新排列。");
            return continuations.get(random.nextInt(continuations.size()));
        } catch (Exception e) {
            return "抱歉，续写时发生错误：" + e.getMessage();
        }
    }

    @Tool(description = "根据关键词生成一段宣传文案或口号")
    public String generateSlogan(
            @ToolParam(description = "产品或概念的关键词") String keyword,
            @ToolParam(description = "目标受众") String audience) {
        try {
            List<String> slogans = new ArrayList<>();
            slogans.add("让" + audience + "拥抱" + keyword + "，开启全新篇章！");
            slogans.add("专为" + audience + "打造的" + keyword + "体验，超越你的想象。");
            slogans.add(keyword + " —— " + audience + "的终极选择。");
            slogans.add("重新定义" + keyword + "，只为" + audience + "的每一个瞬间。");
            slogans.add("当" + audience + "遇上" + keyword + "，灵感一触即发。");
            return slogans.get(random.nextInt(slogans.size()));
        } catch (Exception e) {
            return "抱歉，生成口号时发生错误：" + e.getMessage();
        }
    }
}