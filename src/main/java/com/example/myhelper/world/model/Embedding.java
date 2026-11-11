package com.example.myhelper.world.model;

import java.util.List;

/**
 * 实体内容的向量表示。
 * 向量用于语义表达和相似检索，精确连接仍使用实体编号。
 */
public record Embedding(
        /** 生成向量的模型名称。 */
        String model,

        /** 模型准确版本或权重摘要，不能以模糊的“最新版”替代。 */
        String modelVersion,

        /**
         * 向量分量，维数由列表长度确定；元素必须是有限数。
         * 构造时复制列表，外部不能修改其中的分量。
         */
        List<Double> values) {
    public Embedding {
        if (model == null || model.isBlank()
                || modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("必须提供向量模型及明确版本");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("向量不能为空");
        }
        values = List.copyOf(values);
        for (Double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("向量分量必须是有限数");
            }
        }
    }
}

