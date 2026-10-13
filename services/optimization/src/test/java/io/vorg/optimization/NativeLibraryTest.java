package io.vorg.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import org.junit.jupiter.api.Test;

/** The OR-Tools native library loads on this OS and CP-SAT solves a trivial model (ADR-62 packaging risk). */
class NativeLibraryTest {

    @Test
    void nativeLibraryLoadsAndCpSatSolves() {
        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        IntVar x = model.newIntVar(0, 10, "x");
        IntVar y = model.newIntVar(0, 10, "y");
        model.addLessOrEqual(LinearExpr.sum(new IntVar[] {x, y}), 7);
        model.maximize(LinearExpr.weightedSum(new IntVar[] {x, y}, new long[] {2, 3}));
        CpSolver solver = new CpSolver();
        solver.getParameters().setNumWorkers(1).setRandomSeed(1);
        CpSolverStatus status = solver.solve(model);
        assertEquals(CpSolverStatus.OPTIMAL, status);
        assertEquals(21, solver.objectiveValue(), 1e-9);
        assertTrue(Solvers.orToolsVersion().matches("\\d+\\.\\d+\\.\\d+"), Solvers.orToolsVersion());
        assertEquals("or-tools-cp-sat " + Solvers.orToolsVersion(), Solvers.solverName());
    }
}
