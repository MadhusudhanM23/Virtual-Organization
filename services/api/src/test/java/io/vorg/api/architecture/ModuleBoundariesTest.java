package io.vorg.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "io.vorg", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    /** The module of a class in an internal package is the package prefix before ".internal". */
    private static String moduleOf(String packageName) {
        int idx = packageName.indexOf(".internal");
        return idx < 0 ? packageName : packageName.substring(0, idx);
    }

    private static boolean sameModule(JavaClass origin, JavaClass target) {
        String module = moduleOf(target.getPackageName());
        String originPkg = origin.getPackageName();
        return originPkg.equals(module) || originPkg.startsWith(module + ".");
    }

    /** Classes in {@code ..internal..} are used only from inside their own module. */
    @ArchTest
    static final ArchRule internalsAreNotAccessedFromOtherModules = classes()
            .that().resideInAPackage("..internal..")
            .should(new ArchCondition<JavaClass>("only be accessed from within their own module") {
                @Override
                public void check(JavaClass target, ConditionEvents events) {
                    for (Dependency d : target.getDirectDependenciesToSelf()) {
                        if (!sameModule(d.getOriginClass(), target)) {
                            events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                        }
                    }
                }
            })
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..")
            .orShould().dependOnClassesThat().areAssignableTo(org.springframework.data.repository.Repository.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiNeverDependsOnSimulationInternals = noClasses()
            .that().resideInAPackage("io.vorg.api..")
            .should().dependOnClassesThat().resideInAPackage("io.vorg.simulation..internal..")
            .allowEmptyShould(true);

    /**
     * Isolation (REQ-074): scenarios/simulation runs are computed purely from their own stored document (ADR-60).
     * Nothing in {@code io.vorg.api.scenarios} may depend on a module that writes live tenant state, or on
     * {@code DomainEvents} (the only path onto the event store). The real enforcement is the database grant
     * ({@code vo_sim}, {@code sim} schema only — V1_2_01, {@code SimConfig}); this is the compile-time half.
     */
    @ArchTest
    static final ArchRule scenariosNeverDependOnLiveWriteServices = noClasses()
            .that().resideInAPackage("io.vorg.api.scenarios..")
            .should().dependOnClassesThat().resideInAnyPackage("io.vorg.api.ops..", "io.vorg.api.world..",
                    "io.vorg.api.twin..", "io.vorg.api.incidents..", "io.vorg.api.safety..",
                    "io.vorg.api.integration..", "io.vorg.api.replay..", "io.vorg.api.kpi..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("io.vorg.api.platform.events.DomainEvents")
            .allowEmptyShould(true);
}
