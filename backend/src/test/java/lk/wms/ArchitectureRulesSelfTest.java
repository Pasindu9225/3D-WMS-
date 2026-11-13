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
}
