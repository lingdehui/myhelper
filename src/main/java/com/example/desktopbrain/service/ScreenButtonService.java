package com.example.desktopbrain.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕按钮识别工具：用 Tesseract OCR 扫描屏幕，找到目标文字位置并点击。
 * 用于安装软件时自动确认"下一步""安装""是"等按钮，减少用户手动操作。
 */
@Component
public class ScreenButtonService {

    private static final Robot robot;

    static {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            throw new RuntimeException("无法创建 Robot", e);
        }
    }

    /**
     * 在当前屏幕上查找指定文字，返回中心坐标。
     * 用于让 AI 知道按钮位置，自行调用鼠标点击。
     */
    @Tool(description = """
            在当前屏幕上用OCR查找指定文字，返回找到的文字及坐标。
            常用于查找安装程序中的"下一步""安装""确认""是""同意"等按钮。
            返回格式: "找到 '下一步' 位于 (500,300) 区域 120x40" 或 "未找到"。
            找到后AI应调用 leftClick(x,y) 点击该位置。
            """)
    public String findTextOnScreen(
            @ToolParam(description = "要查找的按钮文字，如'下一步'、'安装'、'确认'、'是'、'同意'、'Next'、'Install'、'Yes'") String targetText) {

        try {
            // 1. 截图
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage screenshot = robot.createScreenCapture(new Rectangle(screen));
            Path tempPng = Files.createTempFile("screen_ocr_", ".png");
            ImageIO.write(screenshot, "png", tempPng.toFile());

            // 2. OCR
            Path tempOut = Files.createTempFile("ocr_tsv_", "");
            String tesseractExe = findTesseract();
            if (tesseractExe == null) {
                return "❌ Tesseract OCR 未安装，请先安装: winget install UB-Mannheim.TesseractOCR";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    tesseractExe,
                    tempPng.toString(),
                    tempOut.toString(),
                    "-l", "chi_sim+eng",
                    "--psm", "6",
                    "tsv"
            );

            // 设置 TESSDATA_PREFIX
            String tessdataPrefix = findTessdataPrefix();
            if (tessdataPrefix != null) {
                pb.environment().put("TESSDATA_PREFIX", tessdataPrefix);
            }

            Process p = pb.start();
            String stderr = new String(p.getErrorStream().readAllBytes());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                return "❌ OCR 执行失败: " + stderr;
            }

            // 3. 解析 TSV 结果
            Path tsvFile = Path.of(tempOut + ".tsv");
            if (!Files.exists(tsvFile)) {
                return "❌ OCR 未生成结果文件";
            }

            List<String> lines = Files.readAllLines(tsvFile);
            List<FoundText> results = new ArrayList<>();

            for (int i = 1; i < lines.size(); i++) {  // 跳过表头
                String[] cols = lines.get(i).split("\t");
                if (cols.length < 12) continue;
                try {
                    int level = Integer.parseInt(cols[0]);
                    if (level != 5) continue;  // level 5 = 单词

                    int left = Integer.parseInt(cols[6]);
                    int top = Integer.parseInt(cols[7]);
                    int width = Integer.parseInt(cols[8]);
                    int height = Integer.parseInt(cols[9]);
                    String text = cols[11].trim();

                    if (text.isEmpty()) continue;

                    // 模糊匹配：包含关系
                    if (text.contains(targetText) || targetText.contains(text)
                            || text.equalsIgnoreCase(targetText)) {
                        int cx = left + width / 2;
                        int cy = top + height / 2;
                        results.add(new FoundText(text, cx, cy, width, height));
                    }
                } catch (NumberFormatException ignored) {}
            }

            // 清理临时文件
            try { Files.deleteIfExists(tempPng); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tsvFile); } catch (IOException ignored) {}

            if (results.isEmpty()) {
                return "未找到 '" + targetText + "' 在当前屏幕上";
            }

            // 合并相邻匹配（同一按钮可能被 OCR 拆成多个词）
            FoundText best = mergeAdjacent(results);
            return String.format("找到 '%s' 位于 (%d,%d) 区域 %dx%d",
                    best.text, best.cx, best.cy, best.width, best.height);

        } catch (Exception e) {
            return "❌ 查找失败: " + e.getMessage();
        }
    }

    /**
     * 一键查找并点击按钮文字，最常用的安装确认操作。
     */
    @Tool(description = """
            在当前屏幕上查找指定文字并用鼠标左键点击。
            常用于自动确认安装程序弹窗，如"下一步""安装""是""确认""Finish"等。
            会先查找文字位置，找到后自动点击，返回操作结果。
            """)
    public String clickTextOnScreen(
            @ToolParam(description = "要点击的按钮文字，如'下一步'、'安装'、'完成'、'是'、'同意'、'Next'、'Install'、'Finish'") String targetText) {

        String findResult = findTextOnScreen(targetText);
        if (findResult.startsWith("❌") || findResult.startsWith("未找到")) {
            return findResult;
        }

        // 解析坐标: "找到 '下一步' 位于 (500,300) 区域 120x40"
        try {
            String[] parts = findResult.split("位于 \\(|\\) 区域");
            String[] coords = parts[1].split(",");
            int x = Integer.parseInt(coords[0].trim());
            int y = Integer.parseInt(coords[1].trim());

            robot.mouseMove(x, y);
            robot.delay(50);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(30);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);

            return "✅ 已点击 '" + targetText + "' 位于 (" + x + "," + y + ")";
        } catch (Exception e) {
            return "❌ 点击失败: " + e.getMessage() + " (原始结果: " + findResult + ")";
        }
    }

    /** 合并相邻的 OCR 匹配结果 */
    private FoundText mergeAdjacent(List<FoundText> results) {
        if (results.size() == 1) return results.get(0);

        // 选置信度最高的那个（这里用区域大小作为简单启发式）
        FoundText best = results.get(0);
        for (FoundText r : results) {
            if (r.width * r.height > best.width * best.height) {
                best = r;
            }
        }
        return best;
    }

    /** 查找 Tesseract 安装路径 */
    private static String findTesseract() {
        // 常见安装路径
        String[] candidates = {
                "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe",
        };
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) return c;
        }
        // 尝试 PATH 中查找
        try {
            Process p = new ProcessBuilder("where", "tesseract").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty()) return out.split("\n")[0].trim();
        } catch (Exception ignored) {}
        return null;
    }

    private static String findTessdataPrefix() {
        String[] candidates = {
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
        };
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) return c;
        }
        // 检查环境变量
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && Files.exists(Path.of(env))) return env;
        return System.getenv("TESSDATA_PREFIX");  // 可能为 null
    }

    private record FoundText(String text, int cx, int cy, int width, int height) {}
}