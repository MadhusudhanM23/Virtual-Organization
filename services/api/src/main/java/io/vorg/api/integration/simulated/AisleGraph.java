package io.vorg.api.integration.simulated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The walkable/drivable lane network inside the warehouse, derived from the rack layout so movers stay in aisles and
 * never cross racks: horizontal aisle lanes between rack rows (and one beyond each end), vertical cross lanes in the
 * gaps between rack columns, and two yard lanes (receiving side near the inbound docks, dispatch side near the outbound
 * docks) that also carry a node in front of every dock. Nodes are lane intersections; edges connect neighbours along a
 * lane. Pure geometry: no randomness, no database. Distances are Euclidean meters in the site-local frame.
 *
 * <p>For Bengaluru Warehouse 01 (racks 9 m long on 12 m column pitch, rows 6 m apart) this yields cross lanes at
 * x = 1, 13, 25, ... 109 and aisle lanes at y = 19, 25, ... 61, yard lanes at y = 8 and y = 72.
 */
public final class AisleGraph {

    /** Layout constants of the yard lanes (meters from the south and north wall of the ground floor). */
    public static final double YARD_LANE_SOUTH = 8.0;
    public static final double YARD_LANE_NORTH = 72.0;
    private static final double RACK_HALF_LENGTH = 4.5;
    private static final double LANE_CLEARANCE = 1.5;

    public record Node(int index, double x, double y) {}

    /** Axis-aligned box in site-local meters. */
    public record Box(double minX, double minY, double maxX, double maxY) {

        public boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<List<Integer>> neighbours = new ArrayList<>();
    private final Map<String, Integer> byKey = new TreeMap<>();

    private AisleGraph() {}

    public static AisleGraph forWarehouse(SimWorld world) {
        AisleGraph g = new AisleGraph();
        TreeSet<Double> rackXs = new TreeSet<>();
        TreeSet<Double> rackYs = new TreeSet<>();
        world.racks().forEach(r -> {
            rackXs.add(r.x());
            rackYs.add(r.y());
        });
        if (rackXs.isEmpty() || rackYs.isEmpty()) {
            throw new IllegalArgumentException("A lane network needs at least one rack");
        }
        List<Double> xs = new ArrayList<>(rackXs);
        List<Double> ys = new ArrayList<>(rackYs);
        List<Double> cross = new ArrayList<>();
        cross.add(xs.get(0) - RACK_HALF_LENGTH - LANE_CLEARANCE);
        for (int i = 0; i + 1 < xs.size(); i++) {
            cross.add((xs.get(i) + xs.get(i + 1)) / 2.0);
        }
        List<Double> aisles = new ArrayList<>();
        aisles.add(ys.get(0) - 3.0);
        for (int i = 0; i + 1 < ys.size(); i++) {
            aisles.add((ys.get(i) + ys.get(i + 1)) / 2.0);
        }
        aisles.add(ys.get(ys.size() - 1) + 3.0);

        List<Double> lanesY = new ArrayList<>();
        lanesY.add(YARD_LANE_SOUTH);
        lanesY.addAll(aisles);
        lanesY.add(YARD_LANE_NORTH);

        // Cross-lane intersections with every lane.
        for (double x : cross) {
            for (double y : lanesY) {
                g.node(x, y);
            }
        }
        // Dock nodes on the yard lanes.
        for (SimWorld.Dock d : world.docks()) {
            g.node(d.x(), "INBOUND".equals(d.direction()) ? YARD_LANE_SOUTH : YARD_LANE_NORTH);
        }
        // Edges: along every lane, between consecutive nodes of that lane; along every cross lane.
        for (double y : lanesY) {
            TreeMap<Double, Integer> lane = new TreeMap<>();
            for (Node n : g.nodes) {
                if (n.y() == y) {
                    lane.put(n.x(), n.index());
                }
            }
            Integer prev = null;
            for (int idx : lane.values()) {
                if (prev != null) {
                    g.connect(prev, idx);
                }
                prev = idx;
            }
        }
        for (double x : cross) {
            Integer prev = null;
            for (double y : lanesY) {
                int idx = g.byKey.get(key(x, y));
                if (prev != null) {
                    g.connect(prev, idx);
                }
                prev = idx;
            }
        }
        return g;
    }

    private static String key(double x, double y) {
        return String.format(java.util.Locale.ROOT, "%.3f|%.3f", x, y);
    }

    private int node(double x, double y) {
        Integer existing = byKey.get(key(x, y));
        if (existing != null) {
            return existing;
        }
        int idx = nodes.size();
        nodes.add(new Node(idx, x, y));
        neighbours.add(new ArrayList<>());
        byKey.put(key(x, y), idx);
        return idx;
    }

    private void connect(int a, int b) {
        neighbours.get(a).add(b);
        neighbours.get(b).add(a);
    }

    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    public Node node(int index) {
        return nodes.get(index);
    }

    public int size() {
        return nodes.size();
    }

    /** Nearest node to a point; ties go to the lowest index (deterministic). */
    public Node nearest(double x, double y) {
        return nearestWithin(x, y, null);
    }

    /** Nearest node inside the box (or overall when none lies inside). */
    public Node nearestWithin(double x, double y, Box box) {
        Node best = null;
        double bestD = Double.MAX_VALUE;
        for (Node n : nodes) {
            if (box != null && !box.contains(n.x(), n.y())) {
                continue;
            }
            double d = Math.hypot(n.x() - x, n.y() - y);
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best != null || box == null ? best : nearestWithin(x, y, null);
    }

    public List<Node> nodesWithin(Box box) {
        if (box == null) {
            return nodes();
        }
        return nodes.stream().filter(n -> box.contains(n.x(), n.y())).toList();
    }

    /** Shortest lane path from one node to another, both included; ties resolve to the lowest node index. */
    public List<Node> path(int from, int to) {
        int n = nodes.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[from] = 0;
        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && (u == -1 || dist[i] < dist[u])) {
                    u = i;
                }
            }
            if (u == -1 || dist[u] == Double.MAX_VALUE) {
                break;
            }
            done[u] = true;
            if (u == to) {
                break;
            }
            for (int v : neighbours.get(u)) {
                double w = Math.hypot(nodes.get(u).x() - nodes.get(v).x(), nodes.get(u).y() - nodes.get(v).y());
                if (dist[u] + w < dist[v] - 1e-12) {
                    dist[v] = dist[u] + w;
                    prev[v] = u;
                }
            }
        }
        List<Node> out = new ArrayList<>();
        if (dist[to] == Double.MAX_VALUE) {
            out.add(nodes.get(from));
            return out;
        }
        for (int at = to; at != -1; at = prev[at]) {
            out.add(0, nodes.get(at));
        }
        return out;
    }
}
