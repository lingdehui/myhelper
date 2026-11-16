package com.example.myhelper.skill;

import com.example.myhelper.world.model.ExecutionStatistics;
import com.example.myhelper.world.model.FailureCause;

import java.util.ArrayList;
import java.util.Objects;

/** 对一个精确技能修订或链路位置计算一次已确认执行的统计变化。 */
public final class SkillUseStatistics {
    private SkillUseStatistics() {
    }

    public static ExecutionStatistics success(ExecutionStatistics current) {
        return new ExecutionStatistics(Math.incrementExact(current.successCount()),
                current.failureCount(), current.failureCauses());
    }

    public static ExecutionStatistics failure(ExecutionStatistics current, FailureCause cause) {
        Objects.requireNonNull(cause);
        var causes = new ArrayList<>(current.failureCauses());
        for (int index = 0; index < causes.size(); index++) {
            FailureCause previous = causes.get(index);
            if (previous.causeId().equals(cause.causeId()) && previous.category() == cause.category()) {
                causes.set(index, withCount(cause, Math.incrementExact(previous.occurrenceCount())));
                return failed(current, causes);
            }
        }
        causes.add(withCount(cause, 1));
        return failed(current, causes);
    }

    private static ExecutionStatistics failed(ExecutionStatistics current, java.util.List<FailureCause> causes) {
        return new ExecutionStatistics(current.successCount(), Math.incrementExact(current.failureCount()), causes);
    }

    private static FailureCause withCount(FailureCause cause, long count) {
        return new FailureCause(cause.causeId(), cause.category(), cause.reason(), cause.analysis(),
                count, cause.evidence());
    }
}
