package archfixture.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Deliberately broken fixtures for ArchitectureRulesSelfTest (SEC-20 / SEC-22). */
public class BadBodyController {

    public record Unbounded(@NotBlank String name) {
    }

    public record Bounded(@NotBlank @Size(max = 20) String name) {
    }

    public static class Mutable {
        public String role;
    }

    @RestController
    public static class NotValid {
        @PostMapping("/a")
        @PreAuthorize("permitAll()")
        public void a(@RequestBody Bounded body) {
        }
    }

    @RestController
    public static class NotRecord {
        @PostMapping("/b")
        @PreAuthorize("permitAll()")
        public void b(@Valid @RequestBody Mutable body) {
        }
    }

    @RestController
    public static class NoLengthLimit {
        @PostMapping("/c")
        @PreAuthorize("permitAll()")
        public void c(@Valid @RequestBody Unbounded body) {
        }
    }
}
