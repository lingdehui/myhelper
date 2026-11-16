package com.example.myhelper.gate;

import com.example.myhelper.capability.CapabilityLookup;
import java.util.Map;

/** 本次运行的动作许可与结果验收，由应用提供业务规则。 */
public interface RuntimeGate {
    /** ref 为空时检查普通工具，否则检查固定版本能力。 */
    boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref);

    /** 检查实际结果是否满足目标。 */
    boolean verify(String goal, Map<String, Object> args, Object output);

    /** 需要按新指令验收时，由业务实现覆盖并核对完整指令。 */
    default boolean verify(String goal, Map<String, Object> args, Object output, Map<String, Object> instructions) {
        return verify(goal, args, output);
    }
}
