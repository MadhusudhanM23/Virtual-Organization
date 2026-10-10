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
}
