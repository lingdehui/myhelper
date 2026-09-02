package com.example.myhelper.memory.vector.category;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类索引路由的无 Spring 单元测试。
 *
 * <p>这里用固定的 Qdrant 命中结果替代外部服务，专门验证“命中目录后从 Neo4j 快照
 * 展开完整子树”的能力。这个约束是快速路径不丢工具、可安全回退的基础。</p>
 */
class ToolCategoryServiceTest {

    @Test
    void recallExpandsTheCompleteSubtreeAndKeepsStableToolOrder() {
        ToolCategoryService service = new StubCategoryService(List.of(
                new ToolCategoryService.CategoryMatch("desktop", "桌面操作", "控制桌面", 0.83)));

        List<ToolCategoryService.CategorySummary> snapshot = List.of(
                category("desktop", "桌面操作", "root", 1, List.of("focusWindow")),
                category("mouse", "鼠标操作", "desktop", 2, List.of("leftClick", "mouseMove")),
                category("keyboard", "键盘操作", "desktop", 2, List.of("pressKey")),
                category("files", "文件管理", "root", 1, List.of("listDirectory")));

        ToolCategoryService.CategoryCandidateRecall result =
                service.recallCandidateTools("点击窗口", 3, 0.45, snapshot);

        assertEquals(List.of("desktop", "keyboard", "mouse"), result.categoryIds());
        assertEquals(List.of("focusWindow", "pressKey", "leftClick", "mouseMove"), result.toolNames());
        assertEquals(0.83, result.bestScore());
    }

    @Test
    void recallSupportsLegacyQdrantPointsByResolvingTheDisplayName() {
        ToolCategoryService service = new StubCategoryService(List.of(
                new ToolCategoryService.CategoryMatch("missing-id", "文件管理", "读写文件", 0.72)));

        List<ToolCategoryService.CategorySummary> snapshot = List.of(
                category("files", "文件管理", "root", 1, List.of("listDirectory")));

        ToolCategoryService.CategoryCandidateRecall result =
                service.recallCandidateTools("列目录", 3, 0.45, snapshot);

        assertEquals(List.of("files"), result.categoryIds());
        assertEquals(List.of("listDirectory"), result.toolNames());
    }

    @Test
    void recallFromALeafDoesNotPullToolsFromSiblingDirectories() {
        ToolCategoryService service = new StubCategoryService(List.of(
                new ToolCategoryService.CategoryMatch("mouse", "鼠标操作", "点击和移动", 0.78)));

        List<ToolCategoryService.CategorySummary> snapshot = List.of(
                category("desktop", "桌面操作", "root", 1, List.of()),
                category("mouse", "鼠标操作", "desktop", 2, List.of("leftClick", "mouseMove")),
                category("keyboard", "键盘操作", "desktop", 2, List.of("pressKey")));

        ToolCategoryService.CategoryCandidateRecall result =
                service.recallCandidateTools("点击", 3, 0.45, snapshot);

        assertEquals(List.of("mouse"), result.categoryIds());
        assertEquals(List.of("leftClick", "mouseMove"), result.toolNames());
    }

    @Test
    void recallReturnsAnEmptyCandidateSetWhenTheIndexHasNoMatch() {
        ToolCategoryService service = new StubCategoryService(List.of());

        ToolCategoryService.CategoryCandidateRecall result = service.recallCandidateTools(
                "未知能力", 3, 0.45, List.of(category("files", "文件管理", "root", 1,
                        List.of("listDirectory"))));

        assertTrue(result.matchedCategories().isEmpty());
        assertTrue(result.categoryIds().isEmpty());
        assertTrue(result.toolNames().isEmpty());
        assertEquals(0.0, result.bestScore());
    }

    private static ToolCategoryService.CategorySummary category(String id, String name, String parentId,
                                                                  int level, List<String> tools) {
        return new ToolCategoryService.CategorySummary(id, name, name + "说明", parentId, level,
                tools.size(), tools, List.of());
    }

    /** 避免测试依赖 Qdrant、Neo4j 或嵌入模型。 */
    private static final class StubCategoryService extends ToolCategoryService {
        private final List<ToolCategoryService.CategoryMatch> matches;

        private StubCategoryService(List<ToolCategoryService.CategoryMatch> matches) {
            super(null, null, null, null, null, null);
            this.matches = matches;
        }

        @Override
        public List<ToolCategoryService.CategoryMatch> searchCategories(String userInput, int topK,
                                                                         double minScore) {
            return matches;
        }
    }
}
