package com.sweepmytv.launcher;

/** JVM checks for TV-safe-area geometry and D-pad column mapping; no Android device required. */
public final class TvLayoutTest {
    public static void main(String[] args) {
        check(TvLayout.SIDE * 2 + TvLayout.COLUMNS * TvLayout.TILE_WIDTH
                + (TvLayout.COLUMNS - 1) * TvLayout.COLUMN_GAP == 960,
                "The six-column grid must fit the 960dp safe area exactly");
        check(TvLayout.DOCK_TOP + TvLayout.DOCK_HEIGHT < TvLayout.CONTENT_TOP,
                "App rows must not overlap the first-row glass material");
        check(TvLayout.DOCK_TOP + TvLayout.DOCK_HEIGHT <= 540,
                "The first row must be completely visible at rest");
        for (int sections : new int[]{0, 1, 2, 12}) for (int count : new int[]{0, 1, 5, 6, 7, 12, 13, 73}) {
            int end = TvLayout.channelTop() + sections * TvLayout.SECTION_HEIGHT;
            check(TvLayout.gridTop(sections) >= end + 40,
                    "The app grid and its heading must follow every content section");
            for (int i = 0; i < count; i++) {
                float screenTop = TvLayout.gridTop(sections) + i / TvLayout.COLUMNS
                        * (TvLayout.TILE_HEIGHT + TvLayout.ROW_GAP) - TvLayout.gridScroll(i, sections);
                check(screenTop >= 80 && screenTop + TvLayout.TILE_HEIGHT * 1.14f + 38 <= 540,
                        "Focused tiles and their captions must remain inside the screen");
            }
        }
        for (int dockCount = 5; dockCount <= 10; dockCount++) {
            check(TvLayout.dockToGrid(0, dockCount) == 0, "Left edges align");
            check(TvLayout.dockToGrid(dockCount - 1, dockCount) == 5, "Right edges align");
            int previous = -1;
            for (int col = 0; col < dockCount; col++) {
                int next = TvLayout.dockToGrid(col, dockCount);
                check(next >= previous && next < 6, "Down preserves horizontal order"); previous = next;
            }
            for (int col = 0; col < 6; col++) {
                int next = TvLayout.gridToDock(col, dockCount);
                check(next >= 0 && next < dockCount, "Up always returns to a real dock tile");
            }
        }
        System.out.println("TV layout checks passed: safe areas, captions, partial rows and 5-10 app column mappings.");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
