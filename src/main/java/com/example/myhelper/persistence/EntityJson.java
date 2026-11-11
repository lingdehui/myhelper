package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

/** 模块专用实体编解码器，完整保留实体和引用，不依赖全局 ObjectMapper 配置。 */
public final class EntityJson {
    /** 支持 record 和时间字段的 JSON 映射器。 */
    private final ObjectMapper mapper;

    /** 为 Instant 注册明确的字符串表示，其他字段使用标准 JSON 映射。 */
    public EntityJson() {
        // 时间适配仅影响本模块的映射器。
        SimpleModule time = new SimpleModule();
        // 以标准 ISO-8601 字符串保存时间，避免本地时区影响。
        time.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            /** 将精确时间写成标准字符串。 */
            @Override
            public void serialize(Instant value, JsonGenerator out, SerializerProvider provider) throws IOException {
                // 保留原始 Instant 的精度与 UTC 语义。
                out.writeString(value.toString());
            }
        });
        // 读取时从标准字符串恢复同一 Instant。
        time.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            /** 从持久化文本还原时间字段。 */
            @Override
            public Instant deserialize(JsonParser input, DeserializationContext context) throws IOException {
                // 格式错误会显式报错，不填造默认时间。
                return Instant.parse(input.getValueAsString());
            }
        });
        // 注册后固定配置，供两个数据库适配器复用。
        mapper = new ObjectMapper().registerModule(time);
    }

    /** 将实体或请求体编码为 JSON。 */
    public String encode(Object value) {
        // 保留完整结构，不筛选业务字段或引用。
        try {
            // 使用当前模块的统一映射规则。
            return mapper.writeValueAsString(value);
        // 编码失败不能发送不完整数据到数据库。
        } catch (IOException error) {
            // 保留原始编码原因。
            throw new IllegalArgumentException("数据无法编码", error);
        }
    }

    /** 解析数据库的 JSON 响应，供适配器检查协议字段。 */
    public JsonNode parse(String json) {
        // 在返回结果之前验证 JSON 格式。
        try {
            // 不解释 payload 中引用的业务含义。
            return mapper.readTree(json);
        // 非 JSON 响应不能被当成成功结果。
        } catch (IOException error) {
            // 保留解析异常便于诊断。
            throw new IllegalArgumentException("无效 JSON 响应", error);
        }
    }

    /** 将数据库中的完整 JSON 还原为 WorldEntity。 */
    public WorldEntity entity(String json) {
        // 复用模型构造器的字段合法性检查。
        try {
            // 类型、正文、引用、证据和向量均完整还原。
            return mapper.readValue(json, WorldEntity.class);
        // 无法还原时显式报告，不返回部分实体。
        } catch (IOException error) {
            // 说明错误发生在持久化实体还原阶段。
            throw new IllegalArgumentException("实体无法还原", error);
        }
    }
}
