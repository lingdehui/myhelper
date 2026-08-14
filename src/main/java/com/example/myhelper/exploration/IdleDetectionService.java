package com.example.myhelper.exploration;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.dialog.DialogStateMachine;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 空闲检测服务。
 * 判定条件：无交互超过阈值 AND 在黑名单时段内。
 * 注：探索使用本地模型不消耗 Token，不做频率限制。
 */
@Service
public class IdleDetectionService {

    private final DialogStateMachine dialogStateMachine;
    private final MyHelperProperties props;

    public IdleDetectionService(DialogStateMachine dialogStateMachine,
                                 MyHelperProperties props) {
        this.dialogStateMachine = dialogStateMachine;
        this.props = props;
    }

    /**
     * 检查是否满足空闲探索条件。
     */
    public boolean shouldExplore() {
        MyHelperProperties.Exploration exp = props.exploration();
        if (!exp.enabled()) return false;

        // 1. 空闲时间检查
        long idleMs = System.currentTimeMillis() - dialogStateMachine.getLastInteractionTime();
        long thresholdMs = exp.idleThresholdMinutes() * 60_000L;
        if (idleMs < thresholdMs) return false;

        // 2. 免打扰时段检查（仅在 blackout-hours 内探索）
        return isInBlackoutHours(exp.blackoutHours());
    }

    private boolean isInBlackoutHours(List<Integer> blackoutHours) {
        // 空列表 = 全天可探索
        if (blackoutHours == null || blackoutHours.isEmpty()) return true;
        int now = LocalTime.now().getHour();
        return blackoutHours.contains(now);
    }
}
