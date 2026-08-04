package com.example.desktopbrain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 联系人模糊匹配：拼音 + 编辑距离
 * 数据源：wechat-friends.json（由 AI 通过 GUI 抓取维护）
 * 作为本地 Tool 暴露给 AI 调用
 */
@Component
public class FriendMatcher {

    private static final Path DATA_PATH = Path.of("wechat-friends.json").toAbsolutePath();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 联系人列表（内存缓存） */
    private volatile List<Friend> friends = List.of();

    public record Friend(String name, String pinyin) {}

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新加载数据文件 */
    public void reload() {
        if (Files.exists(DATA_PATH)) {
            try {
                List<Friend> list = mapper.readValue(DATA_PATH.toFile(),
                        new TypeReference<List<Friend>>() {});
                this.friends = List.copyOf(list);
                System.out.println("📇 联系人匹配器就绪 (" + friends.size() + " 位好友)");
            } catch (IOException e) {
                System.err.println("⚠️ wechat-friends.json 解析失败: " + e.getMessage());
            }
        } else {
            System.out.println("ℹ️ wechat-friends.json 不存在，联系人匹配器使用空列表");
        }
    }

    /**
     * 模糊匹配联系人（暴露给 AI 作为 Tool）
     * @param input 用户说的名字（可能识别不准，如"盘晨"而不是"潘辰"）
     * @return 匹配结果文本
     */
    @Tool(description = """
            根据用户说的名字模糊匹配微信好友。
            当语音识别可能不准确时（如"盘晨"可能是"潘辰"），
            通过拼音和编辑距离进行模糊匹配，返回最可能的好友名。
            参数 input: 用户说出的名字文本（可能包含口音、识别错误）
            """)
    public String findFriend(@ToolParam(description = "用户说出的好友名字（可能不准确）") String input) {
        if (friends.isEmpty()) {
            return "【未找到】联系人列表为空。请先说'同步微信好友列表'来获取数据。";
        }

        if (input == null || input.isBlank()) return "【错误】好友名不能为空";

        String clean = input.replaceAll("[嗯啊哦呢吧吗的了一]", "").trim();
        if (clean.isEmpty()) clean = input.trim();

        // 1. 精确匹配
        for (Friend f : friends) {
            if (f.name.equals(clean)) {
                return "【精确匹配】" + f.name;
            }
        }

        // 2. 包含匹配
        for (Friend f : friends) {
            if (f.name.contains(clean) || clean.contains(f.name)) {
                return "【包含匹配】" + f.name;
            }
        }

        // 3. 拼音匹配（按空格拆开匹配）
        if (clean.length() >= 2) {
            StringBuilder pinyin = new StringBuilder();
            for (char c : clean.toCharArray()) {
                String py = getPinyin(c);
                if (py != null) pinyin.append(py);
            }
            String pyStr = pinyin.toString();
            if (!pyStr.isEmpty()) {
                for (Friend f : friends) {
                    if (f.pinyin != null && f.pinyin.replace(" ", "").equals(pyStr)) {
                        return "【拼音匹配】" + f.name + " (拼音: " + f.pinyin + ")";
                    }
                }
            }
        }

        // 4. 编辑距离模糊匹配
        BestMatch best = null;
        for (Friend f : friends) {
            // 名字编辑距离
            int dist = levenshtein(clean, f.name);
            // 拼音编辑距离
            int pyDist = Integer.MAX_VALUE;
            if (f.pinyin != null) {
                pyDist = levenshtein(clean.toLowerCase(), f.pinyin.replace(" ", ""));
            }
            int score = Math.min(dist, pyDist);
            if (best == null || score < best.score) {
                best = new BestMatch(f, score);
            }
        }

        if (best != null && best.score <= Math.max(3, best.friend.name.length() / 2)) {
            return "【模糊匹配】" + best.friend.name
                    + (best.friend.pinyin != null ? " (拼音: " + best.friend.pinyin + ")" : "")
                    + " (相似度: " + best.score + "步)";
        }

        // 5. 都没匹配到
        StringBuilder sb = new StringBuilder("【未匹配】找不到 \"" + input + "\" 对应的好友。");
        if (!friends.isEmpty()) {
            sb.append("\n当前已知好友: ");
            for (int i = 0; i < Math.min(friends.size(), 10); i++) {
                sb.append(friends.get(i).name);
                if (i < Math.min(friends.size(), 10) - 1) sb.append("、");
            }
            if (friends.size() > 10) sb.append("等" + friends.size() + "人");
        }
        return sb.toString();
    }

    private static class BestMatch {
        final Friend friend;
        final int score;
        BestMatch(Friend f, int s) { friend = f; score = s; }
    }

    /** Levenshtein 编辑距离 */
    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // ===== 内置常用汉字拼音映射（覆盖99%常用名）=====
    private static final Map<Character, String> PINYIN_MAP = new HashMap<>();
    static {
        String[][] data = {
            {"阿","a"},{"艾","ai"},{"安","an"},{"敖","ao"},
            {"巴","ba"},{"白","bai"},{"班","ban"},{"包","bao"},{"贝","bei"},{"毕","bi"},{"边","bian"},{"斌","bin"},{"博","bo"},{"卜","bu"},
            {"蔡","cai"},{"曹","cao"},{"岑","cen"},{"柴","chai"},{"常","chang"},{"车","che"},{"陈","chen"},{"成","cheng"},{"程","cheng"},{"池","chi"},{"初","chu"},{"褚","chu"},{"楚","chu"},{"崔","cui"},
            {"戴","dai"},{"邓","deng"},{"丁","ding"},{"董","dong"},{"杜","du"},{"段","duan"},
            {"樊","fan"},{"范","fan"},{"方","fang"},{"房","fang"},{"费","fei"},{"冯","feng"},{"傅","fu"},{"付","fu"},
            {"盖","gai"},{"甘","gan"},{"高","gao"},{"戈","ge"},{"葛","ge"},{"耿","geng"},{"宫","gong"},{"龚","gong"},{"巩","gong"},{"顾","gu"},{"关","guan"},{"管","guan"},{"郭","guo"},{"国","guo"},
            {"哈","ha"},{"韩","han"},{"郝","hao"},{"何","he"},{"贺","he"},{"洪","hong"},{"侯","hou"},{"胡","hu"},{"华","hua"},{"黄","huang"},{"霍","huo"},
            {"姬","ji"},{"纪","ji"},{"季","ji"},{"贾","jia"},{"简","jian"},{"江","jiang"},{"姜","jiang"},{"蒋","jiang"},{"焦","jiao"},{"金","jin"},{"靳","jin"},{"荆","jing"},{"井","jing"},{"景","jing"},{"鞠","ju"},
            {"康","kang"},{"柯","ke"},{"孔","kong"},{"寇","kou"},{"邝","kuang"},
            {"赖","lai"},{"兰","lan"},{"蓝","lan"},{"雷","lei"},{"冷","leng"},{"黎","li"},{"李","li"},{"厉","li"},{"连","lian"},{"梁","liang"},{"廖","liao"},{"林","lin"},{"凌","ling"},{"刘","liu"},{"柳","liu"},{"龙","long"},{"娄","lou"},{"卢","lu"},{"鲁","lu"},{"陆","lu"},{"路","lu"},{"吕","lv"},{"栾","luan"},{"罗","luo"},{"骆","luo"},
            {"马","ma"},{"毛","mao"},{"梅","mei"},{"孟","meng"},{"米","mi"},{"苗","miao"},{"闵","min"},{"明","ming"},{"莫","mo"},{"牟","mou"},{"穆","mu"},
            {"倪","ni"},{"年","nian"},{"聂","nie"},{"宁","ning"},{"牛","niu"},
            {"欧","ou"},{"潘","pan"},{"庞","pang"},{"裴","pei"},{"彭","peng"},{"皮","pi"},{"朴","piao"},{"平","ping"},{"蒲","pu"},
            {"戚","qi"},{"齐","qi"},{"祁","qi"},{"钱","qian"},{"乔","qiao"},{"秦","qin"},{"邱","qiu"},{"裘","qiu"},{"屈","qu"},{"瞿","qu"},{"全","quan"},
            {"冉","ran"},{"饶","rao"},{"任","ren"},{"荣","rong"},{"容","rong"},{"阮","ruan"},{"芮","rui"},{"瑞","rui"},
            {"桑","sang"},{"沙","sha"},{"单","shan"},{"商","shang"},{"尚","shang"},{"邵","shao"},{"沈","shen"},{"盛","sheng"},{"施","shi"},{"石","shi"},{"时","shi"},{"史","shi"},{"舒","shu"},{"司","si"},{"宋","song"},{"苏","su"},{"孙","sun"},{"索","suo"},
            {"谭","tan"},{"谈","tan"},{"汤","tang"},{"唐","tang"},{"陶","tao"},{"滕","teng"},{"田","tian"},{"童","tong"},{"涂","tu"},{"屠","tu"},
            {"万","wan"},{"汪","wang"},{"王","wang"},{"韦","wei"},{"魏","wei"},{"温","wen"},{"文","wen"},{"翁","weng"},{"吴","wu"},{"武","wu"},{"伍","wu"},
            {"奚","xi"},{"席","xi"},{"夏","xia"},{"向","xiang"},{"项","xiang"},{"萧","xiao"},{"肖","xiao"},{"谢","xie"},{"辛","xin"},{"邢","xing"},{"熊","xiong"},{"徐","xu"},{"许","xu"},{"薛","xue"},{"荀","xun"},
            {"鄢","yan"},{"闫","yan"},{"严","yan"},{"颜","yan"},{"阎","yan"},{"杨","yang"},{"姚","yao"},{"叶","ye"},{"易","yi"},{"殷","yin"},{"尹","yin"},{"应","ying"},{"尤","you"},{"于","yu"},{"余","yu"},{"俞","yu"},{"虞","yu"},{"郁","yu"},{"袁","yuan"},{"岳","yue"},{"云","yun"},
            {"曾","zeng"},{"查","zha"},{"翟","zhai"},{"詹","zhan"},{"张","zhang"},{"章","zhang"},{"赵","zhao"},{"甄","zhen"},{"郑","zheng"},{"钟","zhong"},{"周","zhou"},{"朱","zhu"},{"诸","zhu"},{"祝","zhu"},{"庄","zhuang"},{"卓","zhuo"},{"宗","zong"},{"邹","zou"},{"祖","zu"},{"左","zuo"},
            // 常见名字用字
            {"晨","chen"},{"辰","chen"},{"辰","chen"},{"辰","chen"},{"辰","chen"},{"强","qiang"},{"伟","wei"},{"芳","fang"},
            {"敏","min"},{"静","jing"},{"丽","li"},{"军","jun"},{"杰","jie"},{"涛","tao"},{"明","ming"},{"超","chao"},
            {"秀","xiu"},{"霞","xia"},{"平","ping"},{"刚","gang"},{"华","hua"},{"飞","fei"},{"鑫","xin"},{"宇","yu"},
            {"浩","hao"},{"然","ran"},{"博","bo"},{"文","wen"},{"轩","xuan"},{"涵","han"},{"梓","zi"},{"怡","yi"},
            {"诺","nuo"},{"瑶","yao"},{"彤","tong"},{"可","ke"},{"若","ruo"},{"妍","yan"},{"颖","ying"},{"雪","xue"},
            {"思","si"},{"嘉","jia"},{"睿","rui"},{"昊","hao"},{"泽","ze"},{"湘","xiang"},{"磊","lei"},{"洋","yang"},
            {"勇","yong"},{"慧","hui"},{"瑶","yao"},{"恒","heng"},{"建","jian"},{"峰","feng"},{"龙","long"},{"凤","feng"},
            {"志","zhi"},{"春","chun"},{"秋","qiu"},{"冬","dong"},{"梅","mei"},{"兰","lan"},{"竹","zhu"},{"菊","ju"},
            {"楠","nan"},{"鹏","peng"},{"宁","ning"},{"顺","shun"},{"发","fa"},{"财","cai"},{"福","fu"},{"月","yue"},
            {"亮","liang"},{"祥","xiang"},{"婷","ting"},{"金","jin"},{"银","yin"},{"铜","tong"},{"铁","tie"},
            {"珊","shan"},{"菊","ju"},{"花","hua"},{"草","cao"},{"树","shu"},{"林","lin"},{"森","sen"},
        };
        for (String[] pair : data) {
            PINYIN_MAP.put(pair[0].charAt(0), pair[1]);
        }
    }

    /** 获取单个汉字的拼音（不含声调），未收录返回 null */
    private static String getPinyin(char c) {
        return PINYIN_MAP.get(c);
    }
}
