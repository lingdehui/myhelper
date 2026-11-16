package com.example.myhelper.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 从实体生成无业务含义的字段等值投影；完整 JSON 始终是还原依据。 */
final class QueryTerms {
    /** 工具类无需实例。 */
    private QueryTerms() {
    }

    /** 两库共用相同投影，避免字段类型比较不一致。 */
    static List<String> project(EntityJson json, String encoded) {
        // 保留首次出现顺序并去重数组中的相同值。
        Set<String> terms = new LinkedHashSet<>();
        // 从完整实体快照推导字段，不在此维护业务字段清单。
        collect(json, json.parse(encoded), "", terms);
        // 返回稳定且只读的字符串列表。
        return List.copyOf(terms);
    }

    /** 字段中的数组只支持标量成员匹配，不组合对象数组的不同元素。 */
    private static void collect(EntityJson json, JsonNode node, String pointer, Set<String> terms) {
        // 向量坐标交给向量索引，不建立等值投影。
        if (pointer.equals("/embedding/values")) {
            // 跳过整段坐标，保留模型等其他字段的投影。
            return;
        }
        // 对象逐字段展开，并遵守 JSON Pointer 转义。
        if (node.isObject()) {
            // 使用普通迭代，避免引入 Lambda。
            var fields = node.fields();
            // 遍历对象中实际存在的字段。
            while (fields.hasNext()) {
                // 取得当前键值对。
                var field = fields.next();
                // 转义斜线和波浪号，避免不同字段发生路径冲突。
                String key = field.getKey().replace("~", "~0").replace("/", "~1");
                // 递归记录嵌套对象中的标量。
                collect(json, field.getValue(), pointer + "/" + key, terms);
            }
        // 标量数组支持成员查询，引用对象数组仍完整保存但不自动展开。
        } else if (node.isArray()) {
            // 每个标量元素共享数组本身的字段路径。
            for (JsonNode value : node) {
                // 不把对象数组的跨元素字段拼成伪匹配。
                if (value.isValueNode() && !value.isNull()) {
                    // 使用与标量相同的规范化编码。
                    terms.add(term(json, pointer, value));
                }
            }
        // 缺失和 null 不投影；查询合同也不接受 null 候选。
        } else if (!node.isNull()) {
            // 原类型参与编码，字符串 "1" 不等同于数字 1。
            terms.add(term(json, pointer, node));
        }
    }

    /** 按 AND 条件和条件内 OR 候选生成参数值。 */
    static List<List<String>> groups(EntityJson json, StorageQuery.Filter filter) {
        // 每个内部列表对应一个字段的任一匹配。
        List<List<String>> groups = new ArrayList<>();
        // 只处理调用方提交的条件。
        for (StorageQuery.Match match : filter.matches()) {
            // 收集当前字段的候选词。
            List<String> terms = new ArrayList<>();
            // 保留所有显式候选，避免猜测业务枚举。
            for (Object value : match.values()) {
                // 与实体写入时使用相同的标量规范化。
                terms.add(term(json, match.pointer(), json.parse(json.encode(value))));
            }
            // 固定每组候选参数。
            groups.add(List.copyOf(terms));
        }
        // 外层同样只读。
        return List.copyOf(groups);
    }

    /** 以 JSON 元组编码路径和值，避免手工分隔符碰撞。 */
    private static String term(EntityJson json, String pointer, JsonNode node) {
        // 数字统一十进制形式，使 1 和 1.0 具有相同等值含义。
        if (node.isNumber()) {
            // 保留数字类型标签，与同字面值的字符串区分。
            return json.encode(List.of(pointer, "number", node.decimalValue().stripTrailingZeros().toPlainString()));
        }
        // 布尔同样保留类型信息。
        if (node.isBoolean()) {
            // JSON 布尔不转成字符串。
            return json.encode(List.of(pointer, "boolean", node.booleanValue()));
        }
        // 其余受支持的标量为字符串。
        return json.encode(List.of(pointer, "string", node.asText()));
    }

    /** 仅验证支持且不可变的标量，避免条件中出现可变对象。 */
    static void scalar(Object value) {
        // 字符串、布尔、整数和任意精度十进制无需额外有限性检查。
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            // 这些类型均可稳定序列化。
            return;
        }
        // 浮点值必须排除非有限数。
        if ((value instanceof Float floatValue && Float.isFinite(floatValue)) || (value instanceof Double doubleValue && Double.isFinite(doubleValue))) {
            // 有限数字可按十进制规范化。
            return;
        }
        // 对象、嵌套列表、null 和其他 Number 实现不进入查询。
        throw new IllegalArgumentException("查询值必须是字符串、布尔或有限数字");
    }
}
