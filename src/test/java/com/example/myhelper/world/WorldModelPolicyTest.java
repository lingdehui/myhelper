package com.example.myhelper.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldModelPolicyTest {
    @Test void detectsIndirectCompositionCycle() {
        Map<String, List<String>> graph = Map.of("b", List.of("c"), "c", List.of("a"));
        assertTrue(WorldModelPolicy.introducesCycle("a", "b", graph));
        assertFalse(WorldModelPolicy.introducesCycle("x", "b", graph));
    }
}
