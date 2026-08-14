package com.example.myhelper.generated;

import java.lang.annotation.*;

/**
 * 标识一个工具类是 AI 自动生成的（非手写）。
 *
 * <p>用于区分手写工具和 AI 生成工具，支持：
 * <ul>
 *   <li>自动清理：连续失败3次自动删除生成工具</li>
 *   <li>环境隔离：不同 OS 的生成工具各自管理</li>
 *   <li>审计追踪：可追溯工具的生成来源</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedTool {
    /** 生成时的描述/目的（可选） */
    String description() default "";
    /** 生成的 OS 环境（可选，如 windows-amd64） */
    String environment() default "";
}
