package coolplugin.techtree;

import arc.struct.Seq;

/**
 * Computes a clean top-down visual grid layout for any visible subtree of {@link TechNodeDef}s
 * together with orthogonal connector cell bitmasks (matching vanilla {@code ResearchDialog}'s
 * orthogonal parent-to-child lines).
 *
 * <p>Even grid rows/columns (`2*y`, `2*x`) hold node slots or vertical/horizontal pass-through lines;
 * odd grid rows (`2*y + 1`) hold horizontal distribution bars linking a parent to its children.
 */
public final class TechTreeLayoutEngine {

    public static final int DIR_UP = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_DOWN = 4;
    public static final int DIR_LEFT = 8;

    public static final class GridCanvas {
        public final int rows;
        public final int cols;
        public final TechNodeDef[][] nodes;
        public final int[][] lineMask;
        public final boolean[][] unlockedMask;

        GridCanvas(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.nodes = new TechNodeDef[rows][cols];
            this.lineMask = new int[rows][cols];
            this.unlockedMask = new boolean[rows][cols];
        }
    }

    public interface NodePredicate {
        boolean test(TechNodeDef node);
    }

    private TechTreeLayoutEngine() {}

    /**
     * Builds a 2D {@link GridCanvas} for {@code branchRoot}, pruning any nodes that do not satisfy
     * {@code visiblePredicate} (matching vanilla {@code ResearchDialog}: a node is visible iff its
     * parent is unlocked).
     */
    public static GridCanvas buildCanvas(TechNodeDef branchRoot, NodePredicate visiblePredicate, NodePredicate unlockedPredicate) {
        int[] nextLeafX = {0};
        int[] maxDepth = {0};

        assignCoords(branchRoot, 0, nextLeafX, maxDepth, visiblePredicate);

        int leafCount = Math.max(nextLeafX[0], 1);
        int depthCount = maxDepth[0] + 1;
        int gridCols = leafCount * 2 - 1;
        int gridRows = depthCount * 2 - 1;

        GridCanvas canvas = new GridCanvas(gridRows, gridCols);
        populateCanvas(branchRoot, canvas, visiblePredicate, unlockedPredicate);
        return canvas;
    }

    private static void assignCoords(
            TechNodeDef node,
            int depth,
            int[] nextLeafX,
            int[] maxDepth,
            NodePredicate visiblePredicate) {

        if (depth > maxDepth[0]) maxDepth[0] = depth;
        node.gridY = depth;

        Seq<TechNodeDef> visibleChildren = new Seq<>();
        for (TechNodeDef ch : node.children) {
            if (visiblePredicate.test(ch)) {
                visibleChildren.add(ch);
            }
        }

        if (visibleChildren.isEmpty()) {
            node.gridX = nextLeafX[0]++;
        } else {
            for (TechNodeDef ch : visibleChildren) {
                assignCoords(ch, depth + 1, nextLeafX, maxDepth, visiblePredicate);
            }
            int firstX = visibleChildren.first().gridX;
            int lastX = visibleChildren.peek().gridX;
            node.gridX = (firstX + lastX) / 2;
        }
    }

    private static void populateCanvas(
            TechNodeDef node,
            GridCanvas canvas,
            NodePredicate visiblePredicate,
            NodePredicate unlockedPredicate) {

        int pr = node.gridY * 2;
        int pc = node.gridX * 2;
        canvas.nodes[pr][pc] = node;

        Seq<TechNodeDef> visibleChildren = new Seq<>();
        for (TechNodeDef ch : node.children) {
            if (visiblePredicate.test(ch)) {
                visibleChildren.add(ch);
            }
        }
        if (visibleChildren.isEmpty()) return;

        int busRow = pr + 1;

        for (TechNodeDef child : visibleChildren) {
            boolean edgeUnlocked = unlockedPredicate.test(node) && unlockedPredicate.test(child);
            int cr = child.gridY * 2;
            int cc = child.gridX * 2;

            // Connect parent bottom -> busRow at parent column `pc`
            addDir(canvas, busRow, pc, DIR_UP, edgeUnlocked);

            if (cc == pc) {
                // Straight vertical drop from parent to child
                addDir(canvas, busRow, pc, DIR_DOWN, edgeUnlocked);
            } else if (cc < pc) {
                // Go left along busRow from `pc` to `cc`, then down into child
                addDir(canvas, busRow, pc, DIR_LEFT, edgeUnlocked);
                for (int c = pc - 1; c > cc; c--) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_RIGHT | DIR_DOWN, edgeUnlocked);
            } else {
                // Go right along busRow from `pc` to `cc`, then down into child
                addDir(canvas, busRow, pc, DIR_RIGHT, edgeUnlocked);
                for (int c = pc + 1; c < cc; c++) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_LEFT | DIR_DOWN, edgeUnlocked);
            }

            // If child is more than 1 level below (shouldn't happen in standard tree, but safe):
            for (int r = busRow + 1; r < cr; r++) {
                addDir(canvas, r, cc, DIR_UP | DIR_DOWN, edgeUnlocked);
            }

            populateCanvas(child, canvas, visiblePredicate, unlockedPredicate);
        }
    }

    private static void addDir(GridCanvas canvas, int r, int c, int mask, boolean edgeUnlocked) {
        if (r < 0 || r >= canvas.rows || c < 0 || c >= canvas.cols) return;
        canvas.lineMask[r][c] |= mask;
        if (edgeUnlocked) {
            canvas.unlockedMask[r][c] = true;
        }
    }
}
