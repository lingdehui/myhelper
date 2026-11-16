package com.example.myhelper.llm;

import java.util.List;

/** 外部标签及画像模块提供的只读选型入口。 */
public interface ModelRoutingProvider {
    /**
     * 根据分支任务描述返回标签和按偏好排序的模型连接引用。
     * 使用已有标签或已加载的画像。实现须支持并发读取，并在本地有界返回。
     * 此入口不执行网络、数据库或模型请求。
     * 缺少标签或有效画像时返回空结果，读取故障通过异常报告。
     */
    Route route(ModelAdapter.BranchRequest branch);

    record Route(
            /** 本次排序采用的外部任务标签。 */
            List<String> labels,
            /** 优先级从高到低的连接引用，适配器仍会检查兼容性和占用。 */
            List<String> preferredModelRefs) {
        public Route {
            labels = List.copyOf(labels);
            preferredModelRefs = List.copyOf(preferredModelRefs);
        }
    }
}
