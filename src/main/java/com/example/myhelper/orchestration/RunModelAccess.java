package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import java.util.List;
import java.util.Objects;

/** 循环侧的模型端口接线：只管理会话生命周期和合同校验，不挑选或占用模型。 */
final class RunModelAccess implements AutoCloseable {
    /** 外部模型模块；尚未装配时为空，纯工具流程仍可运行。 */
    private final ModelAdapter adapter;
    /** 本次根运行的固定身份、指定模型和工具范围。 */
    private final ModelAdapter.RunRequest request;
    /** 未请求模型时只保留逻辑身份，不伪造一个实际模型绑定。 */
    private final ModelAdapter.Binding rootContext;
    /** 按需打开的外部会话；绑定和模型选择的权威状态在该会话内。 */
    private ModelAdapter.Session session;
    /** 打开或校验失败后保留异常，其他分支不能反复重试打开。 */
    private RuntimeException openFailure;
    /** 根实际工作结束后禁止重新打开会话。 */
    private boolean closed;

    RunModelAccess(ModelAdapter adapter, ModelAdapter.RunRequest request) {
        // 接受尚未装配的端口，直到实际需要模型时才报错。
        this.adapter = adapter;
        // 本运行共享不可变请求，不能让分支扩大模型和工具边界。
        this.request = Objects.requireNonNull(request);
        // NOT_REQUESTED 只表示逻辑位置，不代表根模型已经参与执行。
        this.rootContext = new ModelAdapter.Binding(request.rootBranchId(), null, null,
                ModelAdapter.Assignment.NOT_REQUESTED);
    }

    // 入口检索及纯工具执行只需要身份，不打开外部模型会话。
    ModelAdapter.Binding rootContext() {
        return rootContext;
    }

    // 真正调用模型前读取外部权威绑定，不能靠工作线程或过期占位决定模型。
    synchronized ModelAdapter.Binding forCall(ModelAdapter.Binding context) {
        // 首次使用时打开，后续线性调用直接使用同一会话。
        ModelAdapter.Session current = session();
        // 未登记、已释放或外部已关闭的绑定必须明确失败。
        ModelAdapter.Binding actual = current.binding(context.id());
        // 适配器不能改变分支身份和父关系，也不能把占位当成真实分配。
        if (actual == null || !actual.id().equals(context.id())
                || !Objects.equals(actual.parentId(), context.parentId())
                || actual.assignment() == ModelAdapter.Assignment.NOT_REQUESTED) {
            throw invalid(context.id());
        }
        // 已经取得的实际绑定应保持不变；根逻辑占位在这里替换为真实根绑定。
        if (context.assignment() != ModelAdapter.Assignment.NOT_REQUESTED && !context.equals(actual)) {
            throw invalid(context.id());
        }
        // 根模型始终由请求指定，不能在后续调用中被适配器悄悄替换。
        if (context.id().equals(request.rootBranchId())) {
            validateRoot(actual);
        }
        return actual;
    }

    // 转交有序子分支及任务描述，可用模型、选择顺序和不足回退由外部实现。
    List<ModelAdapter.Binding> fork(ModelAdapter.Binding parent, List<ModelAdapter.BranchRequest> children) {
        ModelAdapter.Binding actualParent;
        ModelAdapter.Session current;
        synchronized (this) {
            // 可能是首次分叉，先取得已登记的父绑定及本次会话。
            actualParent = forCall(parent);
            current = session;
        }
        // 会话本身支持并发访问，外部排序期间不占用根运行的接线锁。
        List<ModelAdapter.BranchRequest> requests = List.copyOf(children);
        // 分配必须整组原子完成；适配器异常直接上报，不能假装没有其他模型。
        List<ModelAdapter.Binding> allocated = current.forkWithTasks(actualParent, requests);
        // 在写入计划映射前校验整个结果，不能接受半组或多出来的绑定。
        if (allocated == null || allocated.size() != requests.size()) {
            throw invalid(actualParent.id());
        }
        for (int index = 0; index < requests.size(); index++) {
            ModelAdapter.Binding child = allocated.get(index);
            String childId = requests.get(index).branchId();
            // 子身份、顺序、父身份和分配来源都属于端口合同。
            if (child == null || !childId.equals(child.id())
                    || !actualParent.id().equals(child.parentId())) {
                throw invalid(childId);
            }
            // 继承必须沿用父模型，其他模型分配必须实际改变引用。
            switch (child.assignment()) {
                case INHERITED -> {
                    if (!actualParent.modelRef().equals(child.modelRef())) {
                        throw invalid(child.id());
                    }
                }
                case OTHER_MODEL -> {
                    if (actualParent.modelRef().equals(child.modelRef())) {
                        throw invalid(child.id());
                    }
                }
                default -> throw invalid(child.id());
            }
            // 返回的快照必须已经在外部会话登记，后续模型调用才能按身份取回。
            if (!child.equals(current.binding(child.id()))) {
                throw invalid(child.id());
            }
        }
        // 计划只持有不可变返回快照，不接管外部权威分配表。
        return List.copyOf(allocated);
    }

    // 纯工具逻辑分支从未分配模型，不能向外部伪造释放请求。
    synchronized void release(ModelAdapter.Binding binding) {
        if (binding.assignment() == ModelAdapter.Assignment.NOT_REQUESTED) {
            return;
        }
        // 真正取得过的子绑定由外部回收；重复释放由端口保证幂等。
        session().release(binding.id());
    }

    // 同一根运行只允许一次成功打开；元数据方法不得阻塞等待远程模型请求。
    private ModelAdapter.Session session() {
        if (closed) {
            throw new IllegalStateException(LlmTexts.MODEL_ADAPTER_SESSION_CLOSED);
        }
        if (openFailure != null) {
            throw openFailure;
        }
        if (session == null) {
            try {
                // 没有外部实现时明确失败，不在循环里偷偷补一个默认分配器。
                if (adapter == null) {
                    throw new IllegalStateException(LlmTexts.MODEL_ADAPTER_REQUIRED);
                }
                // 先保存会话再校验；即使根合同错误，真正收尾时也能关闭它。
                session = adapter.openRun(request);
                if (session == null) {
                    throw invalid(request.rootBranchId());
                }
                validateRoot(session.root());
            } catch (RuntimeException failure) {
                // 防止并行分支在一次打开失败后反复重建外部运行状态。
                openFailure = failure;
                throw failure;
            }
        }
        return session;
    }

    // 根身份、模型和分配来源必须与调用方输入一致。
    private void validateRoot(ModelAdapter.Binding root) {
        if (root == null || !root.id().equals(request.rootBranchId()) || root.parentId() != null
                || !request.modelRef().equals(root.modelRef()) || root.assignment() != ModelAdapter.Assignment.ROOT) {
            throw invalid(request.rootBranchId());
        }
    }

    // 固定反馈集中在 LlmTexts，具体分支身份由运行时追加。
    private static IllegalStateException invalid(String branchId) {
        return new IllegalStateException(LlmTexts.MODEL_ADAPTER_INVALID_BINDING_PREFIX + branchId);
    }

    @Override
    // 仅由真实工作链收尾调用；对外取消完成时不能提前回收在途分支。
    public synchronized void close() {
        if (closed) {
            return;
        }
        // 先禁止后续使用；关闭异常交由根收尾记录，不重新启动模型会话。
        closed = true;
        if (session != null) {
            session.close();
        }
    }
}
