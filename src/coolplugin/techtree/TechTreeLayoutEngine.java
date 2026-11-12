package coolplugin.techtree;

import arc.struct.Seq;

/**
 * Multi-directional orthogonal grid layout engine around {@code dagger}:
 * <ul>
 *   <li>{@code direction = "up"} branches grow UPWARD above {@code dagger} (`gridY < root.gridY`), like vanilla {@code ResearchDialog}.</li>
 *   <li>{@code direction = "left"} and {@code "right"} sit horizontally to the left and right of {@code dagger} (`gridY == root.gridY`).</li>
 *   <li>{@code direction = "down"} branches grow DOWNWARD below {@code dagger} and the horizontal T1 spine (`gridY > root.gridY`).</li>
 * </ul>
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

    public static GridCanvas buildCanvas(
            TechNodeDef root,
            NodePredicate visiblePredicate,
            NodePredicate unlockedPredicate) {

        Seq<TechNodeDef> upChildren = new Seq<>();
        Seq<TechNodeDef> leftChildren = new Seq<>();
        Seq<TechNodeDef> downChildren = new Seq<>();
        Seq<TechNodeDef> rightChildren = new Seq<>();

        for (TechNodeDef ch : root.children) {
            if (!visiblePredicate.test(ch)) continue;
            String d = ch.direction == null ? TechNodeDef.DIR_DOWN : ch.direction;
            switch (d) {
                case TechNodeDef.DIR_UP -> upChildren.add(ch);
                case TechNodeDef.DIR_LEFT -> leftChildren.add(ch);
                case TechNodeDef.DIR_RIGHT -> rightChildren.add(ch);
                default -> downChildren.add(ch);
            }
        }

        // 1. Lay out UP forest (temporary relY = 1..maxUpDepth)
        int[] nextUpLeafX = {0};
        int[] maxUpDepth = {0};
        for (TechNodeDef upChild : upChildren) {
            assignSubtreeCoords(upChild, 1, nextUpLeafX, maxUpDepth, visiblePredicate);
        }
        int rootY = maxUpDepth[0];
        for (TechNodeDef upChild : upChildren) {
            flipUpYRecursive(upChild, rootY);
        }
        int upAnchorX = upChildren.isEmpty()
            ? 0
            : (upChildren.first().gridX + upChildren.peek().gridX) / 2;

        // 2. Lay out horizontal spine [left(furthest..nearest), root, right(nearest..furthest)] + their DOWN subtrees
        Seq<TechNodeDef> spine = new Seq<>();
        for (int i = leftChildren.size - 1; i >= 0; i--) {
            spine.add(leftChildren.get(i));
        }
        spine.add(root);
        for (int i = 0; i < rightChildren.size; i++) {
            spine.add(rightChildren.get(i));
        }

        int[] nextDownLeafX = {0};
        int[] maxDownRelDepth = {0};
        for (TechNodeDef s : spine) {
            s.gridY = rootY;
            Seq<TechNodeDef> kids = (s == root) ? downChildren : visibleChildren(s, visiblePredicate);
            if (kids.isEmpty()) {
                s.gridX = nextDownLeafX[0]++;
            } else {
                for (TechNodeDef k : kids) {
                    assignSubtreeCoords(k, 1, nextDownLeafX, maxDownRelDepth, visiblePredicate);
                    shiftDownYRecursive(k, rootY);
                }
                s.gridX = (kids.first().gridX + kids.peek().gridX) / 2;
            }
        }

        // 3. Horizontally align UP forest with root.gridX
        if (!upChildren.isEmpty()) {
            int diff = upAnchorX - root.gridX;
            if (diff > 0) {
                for (TechNodeDef s : spine) {
                    s.gridX += diff;
                    Seq<TechNodeDef> kids = (s == root) ? downChildren : visibleChildren(s, visiblePredicate);
                    for (TechNodeDef k : kids) shiftXRecursive(k, diff);
                }
            } else if (diff < 0) {
                int shift = -diff;
                for (TechNodeDef u : upChildren) shiftXRecursive(u, shift);
            }
        }

        // 4. Determine overall bounds and allocate GridCanvas
        int[] maxX = {0};
        int[] maxY = {rootY + maxDownRelDepth[0]};
        findMaxBounds(root, visiblePredicate, maxX, maxY);

        int gridCols = (maxX[0] + 1) * 2 - 1;
        int gridRows = (maxY[0] + 1) * 2 - 1;
        GridCanvas canvas = new GridCanvas(gridRows, gridCols);

        // 5. Place nodes & orthogonal connectors
        canvas.nodes[root.gridY * 2][root.gridX * 2] = root;

        // Upward branches from root
        if (!upChildren.isEmpty()) {
            drawUpConnections(root, upChildren, canvas, visiblePredicate, unlockedPredicate);
        }

        // Horizontal left spine from root
        TechNodeDef prevLeft = root;
        for (int i = 0; i < leftChildren.size; i++) {
            TechNodeDef cur = leftChildren.get(i);
            int sr = root.gridY * 2;
            int cLeft = cur.gridX * 2;
            int cRight = prevLeft.gridX * 2;
            canvas.nodes[sr][cLeft] = cur;
            boolean edgeUnlocked = unlockedPredicate.test(root) && unlockedPredicate.test(cur);
            for (int c = cLeft + 1; c < cRight; c++) {
                addDir(canvas, sr, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
            }
            Seq<TechNodeDef> downKids = visibleChildren(cur, visiblePredicate);
            if (!downKids.isEmpty()) {
                drawDownConnections(cur, downKids, canvas, visiblePredicate, unlockedPredicate);
            }
            prevLeft = cur;
        }

        // Horizontal right spine from root
        TechNodeDef prevRight = root;
        for (int i = 0; i < rightChildren.size; i++) {
            TechNodeDef cur = rightChildren.get(i);
            int sr = root.gridY * 2;
            int cLeft = prevRight.gridX * 2;
            int cRight = cur.gridX * 2;
            canvas.nodes[sr][cRight] = cur;
            boolean edgeUnlocked = unlockedPredicate.test(root) && unlockedPredicate.test(cur);
            for (int c = cLeft + 1; c < cRight; c++) {
                addDir(canvas, sr, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
            }
            Seq<TechNodeDef> downKids = visibleChildren(cur, visiblePredicate);
            if (!downKids.isEmpty()) {
                drawDownConnections(cur, downKids, canvas, visiblePredicate, unlockedPredicate);
            }
            prevRight = cur;
        }

        // Downward branches directly from root
        if (!downChildren.isEmpty()) {
            drawDownConnections(root, downChildren, canvas, visiblePredicate, unlockedPredicate);
        }

        return canvas;
    }

    private static Seq<TechNodeDef> visibleChildren(TechNodeDef node, NodePredicate visiblePredicate) {
        Seq<TechNodeDef> out = new Seq<>();
        for (TechNodeDef ch : node.children) {
            if (visiblePredicate.test(ch)) out.add(ch);
        }
        return out;
    }

    private static void assignSubtreeCoords(
            TechNodeDef node,
            int relDepth,
            int[] nextLeafX,
            int[] maxDepth,
            NodePredicate visiblePredicate) {
        if (relDepth > maxDepth[0]) maxDepth[0] = relDepth;
        node.gridY = relDepth;

        Seq<TechNodeDef> kids = visibleChildren(node, visiblePredicate);
        if (kids.isEmpty()) {
            node.gridX = nextLeafX[0]++;
        } else {
            for (TechNodeDef ch : kids) {
                assignSubtreeCoords(ch, relDepth + 1, nextLeafX, maxDepth, visiblePredicate);
            }
            node.gridX = (kids.first().gridX + kids.peek().gridX) / 2;
        }
    }

    private static void flipUpYRecursive(TechNodeDef node, int rootY) {
        node.gridY = rootY - node.gridY;
        for (TechNodeDef ch : node.children) {
            flipUpYRecursive(ch, rootY);
        }
    }

    private static void shiftDownYRecursive(TechNodeDef node, int rootY) {
        node.gridY = rootY + node.gridY;
        for (TechNodeDef ch : node.children) {
            shiftDownYRecursive(ch, rootY);
        }
    }

    private static void shiftXRecursive(TechNodeDef node, int dx) {
        node.gridX += dx;
        for (TechNodeDef ch : node.children) {
            shiftXRecursive(ch, dx);
        }
    }

    private static void findMaxBounds(TechNodeDef node, NodePredicate visiblePredicate, int[] maxX, int[] maxY) {
        if (node.gridX > maxX[0]) maxX[0] = node.gridX;
        if (node.gridY > maxY[0]) maxY[0] = node.gridY;
        for (TechNodeDef ch : node.children) {
            if (visiblePredicate.test(ch)) {
                findMaxBounds(ch, visiblePredicate, maxX, maxY);
            }
        }
    }

    private static void drawUpConnections(
            TechNodeDef parent,
            Seq<TechNodeDef> kids,
            GridCanvas canvas,
            NodePredicate visiblePredicate,
            NodePredicate unlockedPredicate) {
        int pr = parent.gridY * 2;
        int pc = parent.gridX * 2;
        int busRow = pr - 1;

        for (TechNodeDef child : kids) {
            int cr = child.gridY * 2;
            int cc = child.gridX * 2;
            canvas.nodes[cr][cc] = child;

            boolean edgeUnlocked = unlockedPredicate.test(parent) && unlockedPredicate.test(child);
            addDir(canvas, busRow, pc, DIR_DOWN, edgeUnlocked);

            if (cc == pc) {
                addDir(canvas, busRow, pc, DIR_UP, edgeUnlocked);
            } else if (cc < pc) {
                addDir(canvas, busRow, pc, DIR_LEFT, edgeUnlocked);
                for (int c = pc - 1; c > cc; c--) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_RIGHT | DIR_UP, edgeUnlocked);
            } else {
                addDir(canvas, busRow, pc, DIR_RIGHT, edgeUnlocked);
                for (int c = pc + 1; c < cc; c++) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_LEFT | DIR_UP, edgeUnlocked);
            }

            for (int r = busRow - 1; r > cr; r--) {
                addDir(canvas, r, cc, DIR_UP | DIR_DOWN, edgeUnlocked);
            }

            Seq<TechNodeDef> grandKids = visibleChildren(child, visiblePredicate);
            if (!grandKids.isEmpty()) {
                drawUpConnections(child, grandKids, canvas, visiblePredicate, unlockedPredicate);
            }
        }
    }

    private static void drawDownConnections(
            TechNodeDef parent,
            Seq<TechNodeDef> kids,
            GridCanvas canvas,
            NodePredicate visiblePredicate,
            NodePredicate unlockedPredicate) {
        int pr = parent.gridY * 2;
        int pc = parent.gridX * 2;
        int busRow = pr + 1;

        for (TechNodeDef child : kids) {
            int cr = child.gridY * 2;
            int cc = child.gridX * 2;
            canvas.nodes[cr][cc] = child;

            boolean edgeUnlocked = unlockedPredicate.test(parent) && unlockedPredicate.test(child);
            addDir(canvas, busRow, pc, DIR_UP, edgeUnlocked);

            if (cc == pc) {
                addDir(canvas, busRow, pc, DIR_DOWN, edgeUnlocked);
            } else if (cc < pc) {
                addDir(canvas, busRow, pc, DIR_LEFT, edgeUnlocked);
                for (int c = pc - 1; c > cc; c--) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_RIGHT | DIR_DOWN, edgeUnlocked);
            } else {
                addDir(canvas, busRow, pc, DIR_RIGHT, edgeUnlocked);
                for (int c = pc + 1; c < cc; c++) {
                    addDir(canvas, busRow, c, DIR_LEFT | DIR_RIGHT, edgeUnlocked);
                }
                addDir(canvas, busRow, cc, DIR_LEFT | DIR_DOWN, edgeUnlocked);
            }

            for (int r = busRow + 1; r < cr; r++) {
                addDir(canvas, r, cc, DIR_UP | DIR_DOWN, edgeUnlocked);
            }

            Seq<TechNodeDef> grandKids = visibleChildren(child, visiblePredicate);
            if (!grandKids.isEmpty()) {
                drawDownConnections(child, grandKids, canvas, visiblePredicate, unlockedPredicate);
            }
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
