package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import java.util.List;
import java.util.Objects;

/** 数据库查询合同；字段条件由上层提供，不包含实体归属或业务状态规则。 */
public final class StorageQuery {
    /** 每次读取的保护上限；不自动循环拉取整个库。 */
    public static final int MAX_RESULTS = 1000;

    /** 仅作为查询类型的命名空间。 */
    private StorageQuery() {
    }

    /** 一个字段匹配任一候选值；数组字段表示包含其中一个标量，不关联数组元素。 */
    public record Match(
            /** 实体 JSON Pointer，例如 /data/taskId、/unitType 或 /scope/tenant。 */
            String pointer,
            /** 非空标量候选，支持字符串、布尔及有限数字。 */
            List<Object> values) {
        /** 在访问数据库前检查字段路径和查询值。 */
        public Match {
            // 字段使用标准 JSON Pointer 转义，不把路径拼入数据库语句。
            if (pointer == null || !pointer.startsWith("/") || pointer.matches("(?s).*~(?![01]).*")) {
                // 无效路径不能被当成永远查不到的合法条件。
                throw new IllegalArgumentException("字段必须是合法 JSON Pointer");
            }
            // 向量坐标不建立标量投影，应使用向量搜索。
            if (pointer.equals("/embedding/values") || pointer.startsWith("/embedding/values/")) {
                // 避免把高维向量展开为大量关键词。
                throw new IllegalArgumentException("向量坐标不能作为字段条件");
            }
            // 固定条件内容，拒绝 null 候选。
            values = List.copyOf(values);
            // 每个条件的候选集合也有界。
            limit(values.size());
            // 只接受两库都能一致比较的标量。
            for (Object value : values) {
                // 规范化时同时拒绝对象、数组和非有限数字。
                QueryTerms.scalar(value);
            }
        }
    }

    /** 各条件之间为 AND；空列表表示不按字段筛选。 */
    public record Filter(
            /** 条件只负责数据匹配，不决定读取来源。 */
            List<Match> matches) {
        /** 保存只读条件列表。 */
        public Filter {
            // 不允许调用期间修改筛选条件。
            matches = List.copyOf(matches);
            // 防止提交无限长的数据库谓词。
            if (matches.size() > 32) {
                // 由上层拆分过大的查询。
                throw new IllegalArgumentException("字段条件最多 32 项");
            }
        }

        /** 构造无条件分页扫描；不隐含任何业务类型。 */
        public static Filter all() {
            // 空条件同样由数据库执行有界分页。
            return new Filter(List.of());
        }
    }

    /** 后端分页位置，只能用于同一存储实例、来源和筛选条件。 */
    public record Cursor(
            /** 产生此游标的物理数据库。 */
            DualWriteStore.Database source,
            /** 后端返回的位置，不由业务调用方计算。 */
            String position) {
        /** 拒绝缺失来源和位置。 */
        public Cursor {
            // 明确绑定物理来源，防止跨库误用。
            Objects.requireNonNull(source);
            // 位置格式由对应适配器进一步检查。
            text(position, "cursor.position");
        }
    }

    /** 单页请求；按 ID 顺序分页，不保证跨页读取处于同一数据库快照。 */
    public record PageRequest(
            /** 由调用方指定的字段条件。 */
            Filter filter,
            /** 单页最多返回的实体数量。 */
            int limit,
            /** 首次查询为 null，后续原样传回上一页游标。 */
            Cursor cursor) {
        /** 校验有界分页参数。 */
        public PageRequest {
            // 空筛选应显式使用 Filter.all()。
            Objects.requireNonNull(filter);
            // 限制数据库单次返回量。
            StorageQuery.limit(limit);
        }
    }

    /** 页面携带完整实体和下一页位置，null 游标表示结束。 */
    public record Page(
            /** 不展开引用的完整实体快照。 */
            List<WorldEntity> entities,
            /** 继续同一查询时使用的游标。 */
            Cursor nextCursor) {
        /** 不向调用方暴露可变结果列表。 */
        public Page {
            // 固定本页结果，不缓存读取资格或业务状态。
            entities = List.copyOf(entities);
        }
    }

    /** 向量查询不生成嵌入，模型与版本必须由上层明确提供。 */
    public record VectorRequest(
            /** 已计算的稠密查询向量。 */
            List<Float> vector,
            /** 与存储向量一致的模型名。 */
            String model,
            /** 与存储向量一致的模型版本。 */
            String modelVersion,
            /** 可选字段筛选，空条件用 Filter.all()。 */
            Filter filter,
            /** 最多返回的候选数。 */
            int limit,
            /** 可选后端分数阈值，含义由集合距离度量决定。 */
            Double scoreThreshold) {
        /** 在网络请求前验证向量和边界。 */
        public VectorRequest {
            // 拒绝 null 元素并固定向量。
            vector = List.copyOf(vector);
            // 不接受零维向量作为检索请求。
            if (vector.isEmpty()) {
                // 无向量实体可以保存，但不能成为查询向量。
                throw new IllegalArgumentException("查询向量不能为空");
            }
            // 排除数据库不能正确计算的坐标。
            for (Float value : vector) {
                // 无穷值和 NaN 都不是合法向量。
                if (!Float.isFinite(value)) {
                    // 在发送请求前报告参数问题。
                    throw new IllegalArgumentException("查询向量必须为有限数");
                }
            }
            // 相同维数不代表相同向量空间，必须固定模型。
            text(model, "model");
            // 同模型不同版本也不能自动混检。
            text(modelVersion, "modelVersion");
            // 不默默省略调用方的过滤条件。
            Objects.requireNonNull(filter);
            // 查询结果必须有界。
            StorageQuery.limit(limit);
            // null 代表不设置阈值，其余必须为有限数。
            if (scoreThreshold != null && !Double.isFinite(scoreThreshold)) {
                // 不把非法阈值交给远端。
                throw new IllegalArgumentException("分数阈值必须为有限数");
            }
        }
    }

    /** 分数仅用于排序或筛选，不表示实体内容真实或能力可执行。 */
    public record Hit(
            /** 完整实体，包括其原始引用及嵌入。 */
            WorldEntity entity,
            /** 数据库返回的原始分数。 */
            double score) {
        /** 拒绝缺失内容或无效分数。 */
        public Hit {
            // 不能返回只带 ID 的伪实体。
            Objects.requireNonNull(entity);
            // 协议错误必须向上报告。
            if (!Double.isFinite(score)) {
                // 不默认用零分掩盖缺失或无效数据。
                throw new IllegalArgumentException("检索分数必须为有限数");
            }
        }
    }

    /** 各查询共用的数量边界。 */
    static void limit(int value) {
        // 单次查询不能为零，也不能无限拉取。
        if (value < 1 || value > MAX_RESULTS) {
            // 让调用方显式分页或拆批。
            throw new IllegalArgumentException("查询数量必须在 1 到 " + MAX_RESULTS + " 之间");
        }
    }

    /** 校验不允许空白的查询参数。 */
    static void text(String value, String name) {
        // 空值和纯空白都不能作为 ID 或模型名。
        if (value == null || value.isBlank()) {
            // 仅报告参数名，不输出业务内容。
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
