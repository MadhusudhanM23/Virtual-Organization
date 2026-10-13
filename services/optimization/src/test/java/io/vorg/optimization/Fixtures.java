package io.vorg.optimization;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class Fixtures {

    private Fixtures() {}

    static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static DockReassignmentProblem problem(String name) {
        return DockReassignmentProblem.parse(read(name));
    }

    static DockReassignmentProblem in02() {
        return problem("dock-closure-in-02.problem.json");
    }
}
