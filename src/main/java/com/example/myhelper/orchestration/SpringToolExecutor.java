package com.example.myhelper.orchestration;

import com.example.myhelper.gate.RuntimeGate.ToolCall;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.tools.ToolDirectory.ToolRef;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** 普通 Spring AI 工具执行适配器；资源根据本次参数解析，模型不直接控制线程或锁。 */
public final class SpringToolExecutor implements AgentOrchestrator.ActionExecutor {
    /** 等资源锁时每 50 毫秒重新检查取消；不是工具超时或业务预算。 */
    private static final long LOCK_CANCEL_CHECK_INTERVAL_MILLIS = 50L;

    /** 不可变的回调和版本绑定。 */
    private final Map<String, Dispatch> tools;
    /** 根据具体会话/路径解析资源，不使用工具名称的固定并发标志。 */
    private final ResourceResolver resources;
    /** 跨任务共享的条带锁，数量固定，不随资源数量无限增长。 */
    private final List<ReentrantLock> locks;
    /** 参数及结果转换器。 */
    private final ObjectMapper json = new ObjectMapper();

    // 装配共享工具注册表和资源协调器；同一实例协调不同任务。
    public SpringToolExecutor(Collection<ToolCallback> callbacks, ResourceResolver resources, int lockStripes) {
        this(unversioned(callbacks), resources, lockStripes);
    }

    /** 同一次注册提供回调和版本，后续评分与执行共用这个绑定。 */
    public static SpringToolExecutor withBindings(Collection<ToolDirectory.Binding> bindings, ResourceResolver resources,
                                                  int lockStripes) {
        Map<String, Dispatch> registry = new LinkedHashMap<>();
        for (ToolDirectory.Binding binding : bindings) {
            register(registry, new Dispatch(binding.callback(), binding.ref()));
        }
        return new SpringToolExecutor(registry, resources, lockStripes);
    }

    private SpringToolExecutor(Map<String, Dispatch> registry, ResourceResolver resources, int lockStripes) {
        // 条带锁至少需要一把；数量由调用方传入，影响冲突碰撞率。
        if (lockStripes < 1) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException("资源锁数量必须为正");
        }
        // 冻结工具表，多个任务可以并发读取。
        this.tools = Map.copyOf(registry);
        // 保存根据本次实际参数解析资源键的适配器。
        this.resources = Objects.requireNonNull(resources);
        // 创建固定数量的锁；资源哈希映射到这些锁，不按资源数无限增长。
        List<ReentrantLock> stripeLocks = new ArrayList<>();
        for (int index = 0; index < lockStripes; index++) {
            stripeLocks.add(new ReentrantLock());
        }
        this.locks = List.copyOf(stripeLocks);
    }

    private static Map<String, Dispatch> unversioned(Collection<ToolCallback> callbacks) {
        Map<String, Dispatch> registry = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            register(registry, new Dispatch(callback, null));
        }
        return registry;
    }

    private static void register(Map<String, Dispatch> registry, Dispatch dispatch) {
        String toolName = dispatch.callback().getToolDefinition().name();
        if (registry.putIfAbsent(toolName, dispatch) != null) {
            throw new IllegalArgumentException("重复工具名：" + toolName);
        }
    }

    @Override
    public Optional<ToolRef> resolveTool(String tool) {
        Dispatch dispatch = tools.get(tool);
        return dispatch == null ? Optional.empty() : Optional.ofNullable(dispatch.ref());
    }

    @Override
    public AgentOrchestrator.Outcome execute(ToolRef ref, Map<String, Object> args,
                                             AgentOrchestrator.CallContext context) {
        Dispatch dispatch = tools.get(ref.toolName());
        if (dispatch == null || !ref.equals(dispatch.ref())) {
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null, LlmTexts.TOOL_BINDING_CHANGED);
        }
        return execute(ref.toolName(), dispatch, args, context, null);
    }

    @Override
    // 参数已经由编排器绑定；本方法只负责互斥和实际回调。
    public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
        // 按模型计划给出的工具名查找真实实现。
        Dispatch dispatch = tools.get(tool);
        // 未知工具在开始动作前就明确失败。
        if (dispatch == null) {
            // 返回未开始动作的失败，不误报外部副作用未知。
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null, LlmTexts.TOOL_NOT_REGISTERED_PREFIX + tool);
        }
        return execute(tool, dispatch, args, context, null);
    }

    /** 编排路径强制核对获准派发的身份，包括未绑定版本的旧工具。 */
    AgentOrchestrator.Outcome executeChecked(ToolCall call, AgentOrchestrator.CallContext context,
                                             AgentOrchestrator.ToolDispatchGate gate) {
        Dispatch dispatch = tools.get(call.toolName());
        if (dispatch == null) {
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null,
                    LlmTexts.TOOL_NOT_REGISTERED_PREFIX + call.toolName());
        }
        if (!Objects.equals(call.toolRef(), dispatch.ref())) {
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null, LlmTexts.TOOL_BINDING_CHANGED);
        }
        return execute(call.toolName(), dispatch, call.args(), context, gate);
    }

    private AgentOrchestrator.Outcome execute(String tool, Dispatch dispatch, Map<String, Object> args,
                                              AgentOrchestrator.CallContext context, AgentOrchestrator.ToolDispatchGate gate) {
        // 记录本次已拿到的锁，异常时也能完整释放。
        List<ReentrantLock> acquired = new ArrayList<>();
        // 记录是否已经进入实际回调，用来区分明确失败和副作用未知。
        boolean started = false;
        try {
            // 固定顺序获取条带锁，避免多个资源获取顺序不一致造成死锁。
            SortedSet<Integer> slots = new TreeSet<>();
            for (String key : resources.keys(tool, args)) {
                // 相同槽位只获取一次，并保持原来的升序加锁顺序。
                slots.add(Math.floorMod(key.hashCode(), locks.size()));
            }
            // 按固定顺序取得资源对应的锁，防止多个动作交叉等待。
            for (int slot : slots) {
                // 根据资源哈希槽找到跨任务共享的锁。
                ReentrantLock lock = locks.get(slot);
                // 限时尝试拿锁；没拿到时先检查取消，再继续等待。
                while (!lock.tryLock(LOCK_CANCEL_CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
                    // 在等待或开始动作前检查取消和根截止时间。
                    context.check();
                }
                // 拿锁成功后登记，finally 会逆序释放。
                acquired.add(lock);
            }
            // 在等待或开始动作前检查取消和根截止时间。
            context.check();
            // 将绑定后的 Map 转为回调接收的 JSON 字符串。
            String input = json.writeValueAsString(args);
            ToolRef ref = dispatch.ref();
            if (ref != null) {
                context.reportToolBinding(Map.of(
                        "toolName", ref.toolName(), "sourceInstance", ref.sourceInstance(),
                        "implementationVersion", ref.implementationVersion()));
                context.check();
            }
            // 等锁结束后才消费本次许可；未获许可时不会进入实际工具。
            if (gate != null && !gate.start(context)) {
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null, LlmTexts.TOOL_NOT_ALLOWED);
            }
            // 接下来进入外部动作；此后异常不能断言什么都没发生。
            started = true;
            // 把 JSON 入参和可信运行上下文交给真正工具。
            String output = dispatch.callback().call(input, new ToolContext(Map.of("rootRunId", context.rootRunId(),
                    // 上下文字段包含任务/步骤身份、总截止时间及共享取消信号。
                    "runId", context.runId(), "nodeId", context.nodeId(), "deadline", context.deadline(), "cancellation", context.stop(),
                    // 工具可以报告实际前后证据，具体快照采集由工具自身提供。
                    "taskId", context.taskId(), "operationId", context.nodeId(), "executionContext", context)));
            // 保存工具输出；JSON 保留结构，普通文本保留字符串。
            Object value;
            try {
                // 尝试把工具返回值解析成 JSON，空返回允许为 null。
                value = output == null ? null : json.readValue(output, Object.class);
            }
            // 工具可以返回普通文本，JSON 解析失败不等于工具执行失败。
            catch (java.io.IOException plainText) {
                // 输出不是合法 JSON 时保留原文，不丢弃实际反馈。
                value = output;
            }
            // 表示回调正常返回；业务验收仍由编排器执行。
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, value, null);
        } catch (Exception failure) {
            if (!started && failure instanceof AgentOrchestrator.RunInterrupted interrupted) {
                // 等锁期间被修订/取消，实际工具尚未调用；不能冒充业务执行失败。
                return new AgentOrchestrator.Outcome(interrupted.status, null, interrupted.getMessage());
            }
            // 线程中断需要保留中断标志，方便上层停止。
            if (failure instanceof InterruptedException) {
                // 恢复中断状态，不吞掉调用方的停止请求。
                Thread.currentThread().interrupt();
            }
            // 工具已开始后发生异常不能断言没有副作用，交由上层核对而不自动重试。
            return new AgentOrchestrator.Outcome(started ? AgentOrchestrator.Status.UNKNOWN : AgentOrchestrator.Status.FAILED,
                    // 保留错误类型和原因，此处没有可信的成功输出。
                    null, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            // 按照获取的相反顺序释放所有已持有锁。
            for (int i = acquired.size() - 1; i >= 0; i--) {
                // 释放当前锁，避免失败动作长期阻塞其他任务。
                acquired.get(i).unlock();
            }
        }
    }

    private record Dispatch(
            /** 实际回调。 */
            ToolCallback callback,
            /** 宿主提供的固定身份，旧按名入口可以为空。 */
            ToolRef ref) {
    }

    @FunctionalInterface
    // 资源识别端口，依据具体参数而不是工具名静态并发开关。
    public interface ResourceResolver {
        /** 返回当前调用占用的实际资源键；空列表表示适配方确认无需互斥。 */
        List<String> keys(String tool, Map<String, Object> arguments);
    }
}
