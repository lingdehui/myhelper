package com.example.myhelper.skill;

import java.util.Map;
import java.util.OptionalDouble;

/** 查询固定固化链修订在完整输入下的可靠度，未知证据以空值表示。 */
@FunctionalInterface
public interface SkillChainReliability {
    OptionalDouble score(String skillId, long revision, Map<String, Object> arguments);
}
