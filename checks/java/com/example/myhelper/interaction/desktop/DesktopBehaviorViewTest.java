package com.example.myhelper.interaction.desktop;

import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Style;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DesktopBehaviorViewTest {
    @Test
    void threePropsFitNearBottomRightWithoutCoveringTheMascotOrEachOther() {
        Rectangle work = new Rectangle(-1200, -40, 1200, 840);
        Rectangle mascot = new Rectangle(-98, 642, 86, 150);
        List<Rectangle> occupied = new ArrayList<>();
        for (Slot slot : Slot.values()) {
            Rectangle bounds = DesktopBehaviorView.propBounds(work, mascot, slot, occupied);
            assertNotNull(bounds);
            assertTrue(work.contains(bounds));
            assertFalse(mascot.intersects(bounds));
            for (Rectangle earlier : occupied) {
                assertFalse(earlier.intersects(bounds));
            }
            occupied.add(bounds);
        }
    }

    @Test
    void propPlacementKeepsThePreferredSideWhenThereIsSpace() {
        Rectangle work = new Rectangle(0, 0, 800, 600);
        Rectangle mascot = new Rectangle(350, 180, 86, 150);
        Rectangle left = DesktopBehaviorView.propBounds(work, mascot, Slot.LEFT, List.of());
        Rectangle right = DesktopBehaviorView.propBounds(work, mascot, Slot.RIGHT, List.of());
        Rectangle center = DesktopBehaviorView.propBounds(work, mascot, Slot.CENTER, List.of());
        assertNotNull(left);
        assertNotNull(right);
        assertNotNull(center);
        assertTrue(left.getMaxX() < mascot.getMinX());
        assertTrue(right.getMinX() > mascot.getMaxX());
        assertTrue(center.getMinY() > mascot.getMaxY());
        assertEquals(mascot.getCenterX(), center.getCenterX());
        assertNull(DesktopBehaviorView.propBounds(new Rectangle(0, 0, 12, 12), mascot, Slot.CENTER, List.of()));
    }

    @Test
    void movementIsASingleBoundedStepAndStaysWithinTheSameWorkArea() {
        Rectangle screen = new Rectangle(-1200, -40, 1200, 840);
        Rectangle mascot = new Rectangle(-98, 642, 86, 150);
        Rectangle towardCenter = DesktopBehaviorView.movementBounds(screen, mascot, Slot.CENTER);
        assertTrue(towardCenter.x < mascot.x);
        assertTrue(mascot.x - towardCenter.x <= 36);
        assertEquals(mascot.y, towardCenter.y);
        Rectangle towardEdge = DesktopBehaviorView.movementBounds(screen, mascot, Slot.RIGHT);
        assertTrue(towardEdge.x > mascot.x);
        assertTrue(screen.contains(towardEdge));
    }

    @Test
    void propHitShapesLeaveTransparentCornersAndTheCupHandleHole() {
        for (Style style : Style.values()) {
            var outline = DesktopProp.outline(style);
            assertTrue(outline.contains(22, 20));
            assertFalse(outline.contains(0, 0));
            assertFalse(outline.contains(43, 39));
        }
        var cup = DesktopProp.outline(Style.CUP);
        assertTrue(cup.contains(39, 21));
        assertFalse(cup.contains(34, 21));
    }
}
