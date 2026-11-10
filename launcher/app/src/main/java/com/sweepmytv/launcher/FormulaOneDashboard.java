package com.sweepmytv.launcher;

/** Fixed, mixed-size TV mosaic. Positions are in the launcher's 960 x 540 dp canvas. */
final class FormulaOneDashboard {
    static final int BODY_HEIGHT = 324, SECTION_HEIGHT = 404;
    // x, y, width, height; two compact rows, all six widgets visible together.
    private static final int[][] CELLS = {{40,8,268,308},{322,8,306,178},{642,8,278,178},
            {322,200,146,116},{482,200,146,116},{642,200,278,116}};
    static int[] cell(int index) { return CELLS[Math.max(0, Math.min(5, index))].clone(); }
    static int neighbor(int index, int dx, int dy) {
        int[] from = CELLS[index]; int best = -1; float score = Float.MAX_VALUE;
        float x = from[0] + from[2]/2f, y = from[1] + from[3]/2f;
        for (int i = 0; i < CELLS.length; i++) {
            if (i == index) continue; int[] to = CELLS[i];
            boolean horizontalOverlap = Math.min(from[0]+from[2], to[0]+to[2]) > Math.max(from[0], to[0]);
            boolean verticalOverlap = Math.min(from[1]+from[3], to[1]+to[3]) > Math.max(from[1], to[1]);
            boolean candidate = dx < 0 ? to[0]+to[2] <= from[0]+1 && verticalOverlap
                    : dx > 0 ? to[0] >= from[0]+from[2]-1 && verticalOverlap
                    : dy < 0 ? to[1]+to[3] <= from[1]+1 && horizontalOverlap
                    : dy > 0 && to[1] >= from[1]+from[3]-1 && horizontalOverlap;
            if (!candidate) continue;
            float along = dx != 0 ? Math.abs(to[0]+to[2]/2f-x) : Math.abs(to[1]+to[3]/2f-y);
            float across = dx != 0 ? Math.abs(to[1]+to[3]/2f-y) : Math.abs(to[0]+to[2]/2f-x);
            float overlap = dx != 0 ? Math.min(from[1]+from[3], to[1]+to[3])-Math.max(from[1],to[1]) : Math.min(from[0]+from[2],to[0]+to[2])-Math.max(from[0],to[0]);
            float value = along + across * 2 - overlap * 3;
            if (value < score) { score = value; best = i; }
        }
        return best;
    }
}
