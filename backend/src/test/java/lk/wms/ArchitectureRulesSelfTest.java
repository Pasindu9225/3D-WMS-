package lk.wms;

import static org.junit.jupiter.api.Assertions.assertThrows;

import archfixture.api.UnsecuredController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** Proves the security rules really catch violations (a rule that matches nothing passes silently). */
class ArchitectureRulesSelfTest {

    @Test
    void missingPreAuthorizeIsCaught() {
        var classes = new ClassFileImporter().importClasses(UnsecuredController.class);
        assertThrows(AssertionError.class,
                () -> ArchitectureTest.CONTROLLER_METHODS_NEED_PRE_AUTHORIZE.check(classes));
    }

    @Test
    void findByIdInServiceIsCaught() {
        var classes = new ClassFileImporter().importPackages("archfixture");
        assertThrows(AssertionError.class,
                () -> ArchitectureTest.SERVICES_DO_NOT_LOAD_BY_BARE_ID.check(classes));
    }

    @Test
    void unsafeRequestBodiesAreCaught() {
        for (Class<?> bad : new Class<?>[] {archfixture.api.BadBodyController.NotValid.class,
                archfixture.api.BadBodyController.NotRecord.class, archfixture.api.BadBodyController.NoLengthLimit.class}) {
            var classes = new ClassFileImporter().importClasses(bad, archfixture.api.BadBodyController.Unbounded.class,
                    archfixture.api.BadBodyController.Mutable.class, archfixture.api.BadBodyController.Bounded.class);
            assertThrows(AssertionError.class,
                    () -> ArchitectureTest.REQUEST_BODIES_ARE_VALIDATED_RECORDS.check(classes), bad.getSimpleName());
        }
    }

    @Test
    void stringBuiltSqlIsCaught() {
        var classes = new ClassFileImporter().importClasses(archfixture.repo.StringSqlDao.class);
        assertThrows(AssertionError.class, () -> ArchitectureTest.NO_STRING_BUILT_SQL.check(classes));
    }
}
