package io.vorg.api.kpi;

import io.vorg.api.kpi.KpiService.WindowInfo;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.twin.TwinQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Analytics computed from history: the floor heatmap (from position events in the event store) and the order flow
 * (from the SIM-WMS / SIM-TMS mirrors). Everything is derived from stored rows and carries the data mode of those rows.
 */
@Service
public class AnalyticsService {

    public static final double DEFAULT_CELL_M = 5.0;
    static final double MIN_CELL_M = 1.0;
    static final double MAX_CELL_M = 25.0;
    static final int MAX_CELLS = 20000;
    static final String POSITION_EVENTS = "evt.events (*_POSITION_UPDATED)";

    public record Bounds(double minX, double minY, double maxX, double maxY) {}

    public record FloorInfo(UUID id, String name, int level, Bounds bounds) {}

    public record Cell(int col, int row, double minX, double minY, double maxX, double maxY, long count,
            long entities) {}

    public record Heatmap(UUID siteId, FloorInfo floor, WindowInfo window, double cellSizeM, int cols, int rows,
            String entityType, long totalSamples, long maxCount, String dataMode, List<String> source,
            Instant computedAt, String reason, List<Cell> cells) {}

    public record Stage(long released, long inProgress, long completed) {}

    public record FlowBucket(Instant start, Instant end, Stage inbound, Stage outbound) {}

    public record BacklogRow(String orderType, String status, long count) {}

    public record ShipmentRow(String direction, String state, long count) {}

    public record OrderFlow(UUID siteId, WindowInfo window, long bucketSeconds, Instant computedAt, String dataMode,
            List<String> source, long sampleSize, String reason, List<FlowBucket> buckets, List<BacklogRow> backlog,
            List<ShipmentRow> shipments) {}

    private final JdbcClient jdbc;
    private final AnalyticsAccess access;
    private final Clock clock;

    public AnalyticsService(JdbcClient jdbc, AnalyticsAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public Heatmap heatmap(Subject subject, UUID siteId, UUID floorId, String window, Double cellSize,
            String entityType) {
        access.requireAnalyze(subject, siteId);
        Instant now = clock.instant();
        KpiWindow w = KpiWindow.parse(window, now);
        double cell = cellSize == null ? DEFAULT_CELL_M : cellSize;
        if (cell < MIN_CELL_M || cell > MAX_CELL_M || Double.isNaN(cell)) {
            throw invalid("cell_size", "must be between " + MIN_CELL_M + " and " + MAX_CELL_M + " meters");
        }
        String type = entityType == null || entityType.isBlank() ? null : entityType.trim().toUpperCase(Locale.ROOT);
        if (type != null && !TwinQueryService.ENTITY_TYPES.contains(type)) {
            throw invalid("entity_type", "must be one of " + new TreeSet<>(TwinQueryService.ENTITY_TYPES));
        }
        FloorInfo floor = floor(siteId, floorId);
        int cols = (int) Math.ceil((floor.bounds().maxX() - floor.bounds().minX()) / cell);
        int rows = (int) Math.ceil((floor.bounds().maxY() - floor.bounds().minY()) / cell);
        if ((long) cols * rows > MAX_CELLS) {
            throw invalid("cell_size", "yields more than " + MAX_CELLS + " cells; use a larger cell size");
        }
        String eventType = type == null ? "%\\_POSITION\\_UPDATED" : type + "\\_POSITION\\_UPDATED";
        Bounds b = floor.bounds();
        List<Cell> cells = new ArrayList<>();
        Set<String> modes = new TreeSet<>();
        jdbc.sql("""
                select floor((cast(location -> 'position' ->> 'x' as double precision) - :minx) / :cell) as gx,
                       floor((cast(location -> 'position' ->> 'y' as double precision) - :miny) / :cell) as gy,
                       count(*) as n, count(distinct aggregate_id) as e, string_agg(distinct data_mode, ',') as m
                from evt.events
                where occurred_at >= :f and occurred_at < :t and event_type like :et
                  and (location ->> 'site_id') = :site and (location ->> 'floor_id') = :floor
                  and (location -> 'position' ->> 'x') is not null and (location -> 'position' ->> 'y') is not null
                  and cast(location -> 'position' ->> 'x' as double precision) >= :minx
                  and cast(location -> 'position' ->> 'x' as double precision) < :maxx
                  and cast(location -> 'position' ->> 'y' as double precision) >= :miny
                  and cast(location -> 'position' ->> 'y' as double precision) < :maxy
                group by 1, 2
                """).param("minx", b.minX()).param("miny", b.minY()).param("maxx", b.maxX()).param("maxy", b.maxY())
                .param("cell", cell).param("f", KpiRepository.utc(w.from())).param("t", KpiRepository.utc(w.to()))
                .param("et", eventType).param("site", siteId.toString()).param("floor", floor.id().toString())
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    int col = (int) rs.getDouble("gx");
                    int row = (int) rs.getDouble("gy");
                    if (col < 0 || row < 0 || col >= cols || row >= rows) {
                        return;
                    }
                    double x0 = b.minX() + col * cell;
                    double y0 = b.minY() + row * cell;
                    cells.add(new Cell(col, row, round(x0), round(y0), round(Math.min(x0 + cell, b.maxX())),
                            round(Math.min(y0 + cell, b.maxY())), rs.getLong("n"), rs.getLong("e")));
                    modes.addAll(KpiRepository.splitModes(rs.getString("m")));
                });
        long total = cells.stream().mapToLong(Cell::count).sum();
        long max = cells.stream().mapToLong(Cell::count).max().orElse(0);
        cells.sort((a, c) -> a.row() != c.row() ? Integer.compare(a.row(), c.row()) : Integer.compare(a.col(), c.col()));
        return new Heatmap(siteId, floor, new WindowInfo(w.label(), w.from(), w.to()), cell, cols, rows, type, total,
                max, KpiFormulas.dataMode(modes), List.of(POSITION_EVENTS), now,
                total == 0 ? "no position events on this floor in the window" : null, cells);
    }

    private FloorInfo floor(UUID siteId, UUID floorId) {
        String sql = "select id, name, level, min_x, min_y, max_x, max_y from world.floors where site_id = :s"
                + (floorId == null ? "" : " and id = :f") + " order by level, name limit 1";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("s", siteId);
        if (floorId != null) {
            spec = spec.param("f", floorId);
        }
        return spec.query((rs, n) -> new FloorInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("level"), new Bounds(rs.getDouble("min_x"), rs.getDouble("min_y"), rs.getDouble("max_x"),
                        rs.getDouble("max_y")))).optional()
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested floor was not found for this site."));
    }

    public OrderFlow orderFlow(Subject subject, UUID siteId, String window) {
        access.requireAnalyze(subject, siteId);
        Instant now = clock.instant();
        KpiWindow w = KpiWindow.parse(window, now);
        int n = w.bucketCount();
        long[][][] stages = new long[2][n][3]; // [direction][bucket][released, in_progress, completed]
        Set<String> modes = new TreeSet<>();
        long[] sample = new long[1];
        jdbc.sql("""
                select coalesce(r.payload ->> 'order_type', 'UNKNOWN') as ot, coalesce(r.payload ->> 'status', 'UNKNOWN') as st,
                       floor(extract(epoch from (r.ingested_at - :f)) / :b) as idx, count(*) as n,
                       string_agg(distinct m.data_mode, ',') as mm
                from integ.mirror_revisions r join integ.mirror_records m on m.id = r.mirror_id
                where m.site_id = :s and m.record_kind = 'ORDER' and r.ingested_at >= :f and r.ingested_at < :t
                group by 1, 2, 3
                """).param("s", siteId).param("f", KpiRepository.utc(w.from())).param("t", KpiRepository.utc(w.to()))
                .param("b", (double) w.bucketSeconds())
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    int i = (int) rs.getDouble("idx");
                    int dir = "INBOUND".equals(rs.getString("ot")) ? 0 : "OUTBOUND".equals(rs.getString("ot")) ? 1 : -1;
                    int stage = switch (rs.getString("st")) {
                        case "RELEASED" -> 0;
                        case "IN_PROGRESS" -> 1;
                        case "COMPLETED" -> 2;
                        default -> -1;
                    };
                    if (i >= 0 && i < n && dir >= 0 && stage >= 0) {
                        stages[dir][i][stage] += rs.getLong("n");
                        sample[0] += rs.getLong("n");
                        modes.addAll(KpiRepository.splitModes(rs.getString("mm")));
                    }
                });
        List<BacklogRow> backlog = new ArrayList<>();
        jdbc.sql("""
                select coalesce(payload ->> 'order_type', 'UNKNOWN') as ot, coalesce(payload ->> 'status', 'UNKNOWN') as st,
                       count(*) as n, string_agg(distinct data_mode, ',') as mm
                from integ.mirror_records where site_id = :s and record_kind = 'ORDER' group by 1, 2 order by 1, 2
                """).param("s", siteId).query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    backlog.add(new BacklogRow(rs.getString("ot"), rs.getString("st"), rs.getLong("n")));
                    modes.addAll(KpiRepository.splitModes(rs.getString("mm")));
                });
        List<ShipmentRow> shipments = new ArrayList<>();
        jdbc.sql("""
                select coalesce(payload ->> 'direction', 'UNKNOWN') as d, coalesce(payload ->> 'state', 'UNKNOWN') as st,
                       count(*) as n, string_agg(distinct data_mode, ',') as mm
                from integ.mirror_records where site_id = :s and record_kind = 'SHIPMENT' group by 1, 2 order by 1, 2
                """).param("s", siteId).query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    shipments.add(new ShipmentRow(rs.getString("d"), rs.getString("st"), rs.getLong("n")));
                    modes.addAll(KpiRepository.splitModes(rs.getString("mm")));
                });
        List<FlowBucket> buckets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant start = w.bucketStart(i);
            Instant end = start.plusSeconds(w.bucketSeconds()).isAfter(w.to()) ? w.to()
                    : start.plusSeconds(w.bucketSeconds());
            buckets.add(new FlowBucket(start, end, stage(stages[0][i]), stage(stages[1][i])));
        }
        boolean hasSource = !backlog.isEmpty() || !shipments.isEmpty();
        String reason = !hasSource ? "no mirrored orders or shipments for this site"
                : sample[0] == 0 ? "no order status changes recorded in the window" : null;
        long backlogTotal = backlog.stream().mapToLong(BacklogRow::count).sum();
        return new OrderFlow(siteId, new WindowInfo(w.label(), w.from(), w.to()), w.bucketSeconds(), now,
                KpiFormulas.dataMode(modes), List.of("integ.mirror_revisions", "integ.mirror_records"),
                sample[0] + backlogTotal, reason, buckets, backlog, shipments);
    }

    private static Stage stage(long[] v) {
        return new Stage(v[0], v[1], v[2]);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more parameters are invalid.", List.of(new FieldError(field, message)));
    }
}
