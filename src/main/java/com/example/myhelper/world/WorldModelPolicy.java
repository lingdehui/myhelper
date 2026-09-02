package com.example.myhelper.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 不依赖存储的递归结构规则。 */
public final class WorldModelPolicy {
    private WorldModelPolicy() {}

    public static void requireValidObject(WorldObject object) {
        if (object == null || blank(object.id()) || blank(object.type()) || blank(object.name())) {
            throw new IllegalArgumentException("世界对象必须包含 id、type 和 name");
        }
        if (object.id().equals(object.parentId()) || object.componentIds().contains(object.id())) {
            throw new IllegalArgumentException("世界对象不能直接包含自身");
        }
    }

    public static void requireValidContext(ContextUnit unit) {
        if (unit == null || blank(unit.id()) || unit.role() == null || blank(unit.subjectId()) || blank(unit.predicate())) {
            throw new IllegalArgumentException("ContextUnit 必须包含 id、subjectId 和 predicate");
        }
        if (unit.operator() != ContextUnit.Operator.EXISTS
                && (unit.objectId() == null) == (unit.literalValue() == null)) {
            throw new IllegalArgumentException("ContextUnit 的 objectId 与 literalValue 必须且只能提供一个");
        }
        if (unit.role() == ContextUnit.Role.STATE && !unit.id().equals(unit.stateId())) {
            throw new IllegalArgumentException("STATE 的 stateId 必须等于自身 id");
        }
        if (unit.role() != ContextUnit.Role.STATE && blank(unit.stateId())) {
            throw new IllegalArgumentException(unit.role() + " 必须引用标准 stateId");
        }
    }

    public static boolean introducesCycle(String parentId, String childId,
                                           Map<String, ? extends Iterable<String>> components) {
        if (parentId.equals(childId)) return true;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(childId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (parentId.equals(current)) return true;
            Iterable<String> children = components.get(current);
            if (children != null) children.forEach(pending::addLast);
        }
        return false;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
