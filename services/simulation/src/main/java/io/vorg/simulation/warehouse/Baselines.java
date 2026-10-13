package io.vorg.simulation.warehouse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Built-in baseline model inputs shipped as classpath resources. */
public final class Baselines {

    /** The Bengaluru Warehouse 01 baseline (layout/resources copied from the SYNTHETIC world seed v1). */
    public static final String BENGALURU_WAREHOUSE_01 = "bengaluru-warehouse-01";

    private static final List<String> NAMES = List.of(BENGALURU_WAREHOUSE_01);

    private Baselines() {}

    public static List<String> names() {
        return NAMES;
    }

    public static ModelInput load(String name) {
        return ModelInput.parse(text(name));
    }

    /** The raw resource JSON of a built-in baseline. */
    public static String text(String name) {
        if (!NAMES.contains(name)) {
            throw new InvalidInputException("scenario.baseline: unknown baseline '" + name + "' (known: " + NAMES + ")");
        }
        String path = "/io/vorg/simulation/warehouse/" + name + ".model.v1.json";
        try (InputStream in = Baselines.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
