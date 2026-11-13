package lk.wms;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "lk.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final List<String> MODULES = List.of(
            "auth", "tenant", "layout", "catalog", "inbound", "inventory", "outbound", "task", "report");

    /** SEC-09: deny by default. A new endpoint without a permission fails the build. */
    @ArchTest
    static final ArchRule CONTROLLER_METHODS_NEED_PRE_AUTHORIZE = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().arePublic()
            .should().beAnnotatedWith(PreAuthorize.class)
            .allowEmptyShould(true)
            .because("SEC-09: every endpoint needs a permission check");

    /** SEC-10: services load by public ID + tenant, never by bare primary key. */
    @ArchTest
    static final ArchRule SERVICES_DO_NOT_LOAD_BY_BARE_ID = noClasses()
            .that().resideInAPackage("..service..")
            .should().callMethodWhere(target(nameMatching(
                    "(find|get|exists|delete)(All)?ById|getReferenceById|getOne")))
            .allowEmptyShould(true)
            .because("SEC-10: use findByPublicIdAndTenant to prevent IDOR");

    @ArchTest
    static final ArchRule CONTROLLERS_DO_NOT_USE_REPOSITORIES = noClasses()
            .that().resideInAPackage("lk.wms..api..")
            .should().dependOnClassesThat().resideInAPackage("lk.wms..repo..")
            .allowEmptyShould(true)
            .because("controllers go through services, which own transactions and tenant checks");

    @ArchTest
    static final ArchRule COMMON_DOES_NOT_DEPEND_ON_MODULES = noClasses()
            .that().resideInAPackage("lk.wms.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    MODULES.stream().map(m -> "lk.wms." + m + "..").toArray(String[]::new))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule CONSTRUCTOR_INJECTION_ONLY = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /**
     * SEC-23: no query is built from strings at runtime. Queries live in Spring Data derived
     * methods or @Query annotations (compile-time constants with :params). Native/raw SQL also
     * bypasses the @TenantId filter, which is a second reason to block it.
     */
    @ArchTest
    static final ArchRule NO_STRING_BUILT_SQL = noClasses()
            .should().callMethodWhere(target(nameMatching("create(Native|Stored)?\\w*Query"))
                    .and(target(owner(assignableTo("jakarta.persistence.EntityManager")))))
            .orShould().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc.core..", "java.sql..")
            .because("SEC-23: parameterised queries only; also keeps the tenant filter in force");

    /** Modules talk to each other only through service packages. */
    @ArchTest
    static void modulesTalkOnlyThroughServices(JavaClasses classes) {
        for (String m : MODULES) {
            String base = "lk.wms." + m;
            noClasses().that().resideOutsideOfPackage(base + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            base + ".api..", base + ".domain..", base + ".repo..")
                    .allowEmptyShould(true)
                    .because("other modules may only use " + m + " through " + base + ".service")
                    .check(classes);
        }
    }
}
