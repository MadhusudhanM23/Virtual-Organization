package io.vorg.simulation.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void tallyMomentsAndNearestRankQuantiles() {
        Tally t = new Tally("w");
        for (int i = 1; i <= 10; i++) {
            t.add(i);
        }
        assertEquals(10, t.count());
        assertEquals(5.5, t.mean(), 1e-12);
        assertEquals(Math.sqrt(55.0 / 6.0), t.sd(), 1e-12);
        assertEquals(1, t.min());
        assertEquals(10, t.max());
        assertEquals(10, t.quantile(0.95));
        assertEquals(5, t.quantile(0.5));
        assertEquals(3, t.countAbove(7));
        t.reset(0);
        assertEquals(0, t.count());
        assertEquals(0, t.mean());
        assertEquals(0, t.quantile(0.5));
    }

    @Test
    void timeWeightedAverageAndReset() {
        TimeWeighted q = new TimeWeighted("q", 0);
        q.update(10, 2); // 0 on [0,10)
        q.update(20, 1); // 2 on [10,20)
        assertEquals((0 * 10 + 2 * 10 + 1 * 20) / 40.0, q.mean(40), 1e-12);
        assertEquals(2, q.max());
        q.reset(40);
        assertEquals(1, q.mean(40));
        q.update(50, 3);
        assertEquals((1 * 10 + 3 * 10) / 20.0, q.mean(60), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> q.update(10, 0));
    }

    @Test
    void replicationSummaryUsesStudentT() {
        ReplicationSummary s = ReplicationSummary.of(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        assertEquals(5, s.n());
        assertEquals(3.0, s.mean(), 1e-12);
        assertEquals(Math.sqrt(2.5), s.sd(), 1e-12);
        assertEquals(2.776 * Math.sqrt(2.5) / Math.sqrt(5), s.ci95HalfWidth(), 1e-12);
        assertEquals(s.mean() - s.ci95HalfWidth(), s.ci95Low(), 1e-12);
        assertTrue(Double.isNaN(ReplicationSummary.of(List.of(7.0)).ci95HalfWidth()));
        assertTrue(Double.isNaN(ReplicationSummary.of(List.of()).mean()));
    }

    @Test
    void studentTTable() {
        assertEquals(12.706, StudentT.quantile975(1));
        assertEquals(2.262, StudentT.quantile975(9));
        assertEquals(2.042, StudentT.quantile975(30));
        assertEquals(1.960, StudentT.quantile975(1000));
        assertThrows(IllegalArgumentException.class, () -> StudentT.quantile975(0));
    }

    @Test
    void counter() {
        Counter c = new Counter("c");
        c.increment();
        c.add(4);
        assertEquals(5, c.count());
        c.reset(0);
        assertEquals(0, c.count());
    }
}
