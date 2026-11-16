package com.example.myhelper.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 独立模型模块的接入合同：由实现方选择、绑定和回收模型，循环侧只提交分支需求。
 * 本文件只声明接口及不可变数据，不提供模型目录、默认分配器或真实客户端实现。
 */
public interface ModelAdapter {
    /**
     * 为一个根运行建立独立的模型绑定会话，根模型由请求明确指定。
     * 实现方负责保存该运行的绑定和配置依据；绑定本身不长期占用模型请求并发许可。
     * 不得因打开会话而发起 LLM 请求，也不得将配置或服务错误当作没有其他模型。
     */
    Session openRun(RunRequest request);

    /**
     * 把已确定的配置引用解析为可调用的模型，可接入 AgentCaller 的现有解析函数。
     * 本方法不重新择模，不读取 Java 工作线程身份，不静默换成其他模型；不可用时明确报错。
     */
    ChatModel resolve(String modelRef);

    /** 调用协议明确要求的模型能力，不由业务工具白名单是否为空推断。 */
    enum Requirement {
        /** 模型必须能按本次协议返回原生结构化工具调用。 */
        TOOL_CALLING
    }

    public record RunRequest(
            /** 一次实际根运行的身份，用于隔离不同任务的模型绑定。 */
            String rootRunId,
            /** 根逻辑分支身份，与 Java 工作线程 ID 无关。 */
            String rootBranchId,
            /** 调用方为根分支指定的已配置模型引用。 */
            String modelRef,
            /** 本运行允许的业务工具名称；空列表不表示规划协议不使用工具调用。 */
            List<String> toolNames,
            /** 本运行调用协议明确要求的模型能力，根与子分支使用同一份要求。 */
            Set<Requirement> requirements) {
        // 固定运行合同，外部列表后续变化不能扩大本运行的工具范围。
        public RunRequest {
            // 运行身份用于区分各自独立的绑定状态。
            requireText(rootRunId, "rootRunId");
            // 根分支必须有可追溯的逻辑身份。
            requireText(rootBranchId, "rootBranchId");
            // 根模型不能靠线程或全局当前变量隐式决定。
            requireText(modelRef, "modelRef");
            // 不可变列表允许没有工具，但不允许列表或元素为 null。
            toolNames = List.copyOf(toolNames);
            // 固定协议能力，后续分叉不能因为外部集合变化而放宽筛选。
            requirements = Set.copyOf(requirements);
        }

        /** 保留原调用方式，保守要求工具调用；纯文本调用应显式传入空能力集合。 */
        public RunRequest(String rootRunId, String rootBranchId, String modelRef, List<String> toolNames) {
            // 原接口未区分业务工具与规划控制工具，不能据空白名单自动降低要求。
            this(rootRunId, rootBranchId, modelRef, toolNames, Set.of(Requirement.TOOL_CALLING));
        }
    }

    /** 分配前已知的模型任务描述，供外部模块提供标签及候选顺序。 */
    record RoutingTask(
            /** 当前任务目标。 */
            String goal,
            /** 该任务可用的业务工具名称。 */
            List<String> toolNames) {
        public RoutingTask {
            requireText(goal, "routingTask.goal");
            toolNames = List.copyOf(toolNames);
        }
    }

    /** 一个逻辑子分支的选型输入。 */
    record BranchRequest(
            /** 待分配的子分支身份。 */
            String branchId,
            /** 该分支已知的模型任务，按计划顺序提供。 */
            List<RoutingTask> tasks) {
        public BranchRequest {
            requireText(branchId, "branchId");
            tasks = List.copyOf(tasks);
        }
    }

    /** 模型分配来源；用途为运行记录，不是由模型输出决定的调度命令。 */
    enum Assignment {
        /** 根请求明确指定的模型。 */
        ROOT,
        /** 适配器为分支选中的其他兼容模型。 */
        OTHER_MODEL,
        /** 其他模型不足时继承父分支模型。 */
        INHERITED,
        /** 纯工具段的逻辑上下文，尚未请求模型绑定，禁止据此调用模型。 */
        NOT_REQUESTED
    }

    public record Binding(
            /** 根运行内部的逻辑分支身份，不是线程 ID 或工具调用 ID。 */
            String id,
            /** 创建本分支的父分支身份；根逻辑上下文为空。 */
            String parentId,
            /** 已确定的模型配置引用；NOT_REQUESTED 时允许为空。 */
            String modelRef,
            /** 根指定、适配器分配、父模型继承或尚未请求。 */
            Assignment assignment) {
        // 在数据边界拒绝不完整绑定，不在这里实现任何模型选择策略。
        public Binding {
            // 每个绑定或纯工具逻辑上下文都必须能精确定位。
            requireText(id, "branch.id");
            // 缺少分配来源时不能判断是否允许进入模型调用。
            Objects.requireNonNull(assignment, "assignment");
            // 纯工具逻辑上下文可以尚未取得模型，但给出的引用仍须有效。
            if (assignment == Assignment.NOT_REQUESTED) {
                // 只校验已经提供的引用，不凭空构造根模型或默认模型。
                if (modelRef != null) {
                    requireText(modelRef, "branch.modelRef");
                }
                // 纯工具段既可能属于根，也可能属于某个子分支。
                if (parentId != null) {
                    requireText(parentId, "branch.parentId");
                }
            } else {
                // 实际模型绑定必须能交给单次调用的解析函数。
                requireText(modelRef, "branch.modelRef");
                // 根实际绑定不允许声明父分支。
                if (assignment == Assignment.ROOT) {
                    if (parentId != null) {
                        throw new IllegalArgumentException(LlmTexts.BRANCH_BINDING_ROOT_HAS_PARENT);
                    }
                } else {
                    // 其他模型分配和继承都必须指明父分支。
                    requireText(parentId, "branch.parentId");
                }
            }
        }
    }

    /**
     * 一个根运行的模型绑定会话；实现方保证并发访问、分配及回收的一致性。
     * 分支绑定、候选选择和引用计数都由实现方维护，循环侧不另存权威分配表。
     */
    interface Session extends AutoCloseable {
        /** 返回本运行固定的根绑定，其 assignment 必须为 ROOT，不能为 NOT_REQUESTED。 */
        Binding root();

        /** 读取已登记的活动绑定；不存在、已经释放或会话已关闭时明确报错。 */
        Binding binding(String branchId);

        /**
         * 为同一父分支下至少两个逻辑子分支原子申请模型绑定。
         * 实现方按子身份列表的稳定顺序返回同样长度的不可变列表，逐项保留对应身份及父身份。
         * 可分配的其他模型有几个就分配几个，剩余项继承父模型；父绑定保持原样。
         * 父绑定必须是本会话的活动实际绑定，NOT_REQUESTED 不能作为分配依据。
         * 同一父绑定与同一有序子身份批次重复申请时，返回原有结果，不重复分配或增加引用数。
         * 同批部分失败不得留下部分新绑定；身份冲突、不同批次复用身份或已释放批次重开须明确拒绝。
         * 模型目录、筛选顺序、状态保存、回退和引用计数均由外部实现，不由循环侧提供候选列表。
         */
        List<Binding> fork(Binding parent, List<String> childIds);

        /**
         * 为整组子分支提供分配前任务描述。沿用 fork 的身份顺序、原子登记和重放合同。
         * 只实现旧入口的适配器仍可按分支身份分配。
         */
        default List<Binding> forkWithTasks(Binding parent, List<BranchRequest> children) {
            List<String> childIds = new ArrayList<>();
            for (BranchRequest child : children) {
                childIds.add(child.branchId());
            }
            return fork(parent, List.copyOf(childIds));
        }

        /**
         * 回收已经结束的子分支，返回是否产生新的回收；重复回收和根身份返回 false。
         * 有活动子分支时拒绝提前回收；继承父模型的子分支结束不能释放父绑定。
         * 仅回收绑定，不表示撤销已经发送的模型请求或外部工具效果。
         */
        boolean release(String branchId);

        /**
         * 根运行真实结束后关闭会话并回收其绑定，重复关闭不得重复释放资源。
         * 调用方应在在途工作收束后调用；该操作不承诺中断已经发送的请求或撤销工具效果。
         */
        @Override
        void close();
    }

    // 仅做不可变数据的通用必填检查，避免模型接口反向依赖编排器或计划类。
    private static void requireText(String value, String name) {
        // 使用统一反馈片段，调用方可准确识别哪个字段缺失。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + LlmTexts.REQUIRED_TEXT_SUFFIX);
        }
    }
}
