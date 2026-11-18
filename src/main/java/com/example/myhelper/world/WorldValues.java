package com.example.myhelper.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 生成稳定的 JSON 值快照，保留类型、数组次序与十进制值。 */
final class WorldValues {
    /** 世界值专用编解码，保留数据库 JSON 值旁的精确规范文本。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 限制递归 JSON 深度，拒绝循环集合。 */
    private static final int MAX_DEPTH = 64;

    private WorldValues() {
    }

    static Object freeze(Object value) {
        return freeze(value, 0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> value) {
        return value == null ? Map.of() : (Map<String, Object>) freeze(value);
    }

    private static Object freeze(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("世界数据超过 JSON 深度上限");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return decimal(number);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(freeze(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("世界数据的对象键必须为字符串");
                }
                copy.put(key, freeze(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("世界数据必须是 JSON 值");
    }

    private static BigDecimal decimal(Number number) {
        if (number instanceof Double d && !Double.isFinite(d)
                || number instanceof Float f && !Float.isFinite(f)) {
            throw new IllegalArgumentException("世界数据必须使用有限数字");
        }
        if (number instanceof BigDecimal || number instanceof BigInteger || number instanceof Double
                || number instanceof Float || number instanceof Long || number instanceof Integer
                || number instanceof Short || number instanceof Byte) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        throw new IllegalArgumentException("不支持的 JSON 数字类型");
    }

    static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(freeze(value));
        } catch (IOException error) {
            throw new IllegalArgumentException("无法编码世界数据", error);
        }
    }

    static Object decode(String canonical) {
        try {
            return freeze(JSON.readValue(canonical, Object.class));
        } catch (IOException error) {
            throw new IllegalArgumentException("无法读取规范世界数据", error);
        }
    }
}
