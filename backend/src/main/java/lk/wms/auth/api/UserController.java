package lk.wms.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lk.wms.auth.service.UserService;
import lk.wms.auth.service.UserService.Invite;
import lk.wms.auth.service.UserService.RoleView;
import lk.wms.auth.service.UserService.UserView;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UserController {

    private static final String ROLE_CODE = "[A-Z_]{2,40}";
    private static final Map<String, String> SORTS = Map.of(
            "fullName", "fullName", "email", "email", "createdAt", "createdAt", "status", "status");

    public record InviteRequest(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotEmpty @Size(max = 8) List<@NotBlank @Pattern(regexp = ROLE_CODE) String> roles) {
    }

    public record RenameRequest(@NotBlank @Size(max = 120) String fullName) {
    }

    public record RolesRequest(@NotEmpty @Size(max = 8) List<@NotBlank @Pattern(regexp = ROLE_CODE) String> roles) {
    }

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('users:read')")
    public PageResponse<UserView> list(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(users.list(q, PageResponse.request(page, size, sort, SORTS, Sort.by("fullName", "id"))),
                u -> u);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('users:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Invite invite(@Valid @RequestBody InviteRequest body) {
        return users.invite(CurrentUser.require(), body.email(), body.fullName(), body.roles());
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('users:manage')")
    public UserView rename(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RenameRequest body) {
        return users.rename(CurrentUser.require(), id, body.fullName());
    }

    @PostMapping("/users/{id}/disable")
    @PreAuthorize("hasAuthority('users:manage')")
    public UserView disable(@PathVariable @Size(max = 26) String id) {
        return users.disable(CurrentUser.require(), id);
    }

    @PostMapping("/users/{id}/enable")
    @PreAuthorize("hasAuthority('users:manage')")
    public UserView enable(@PathVariable @Size(max = 26) String id) {
        return users.enable(CurrentUser.require(), id);
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('users:manage')")
    public UserView setRoles(@PathVariable @Size(max = 26) String id, @Valid @RequestBody RolesRequest body) {
        return users.setRoles(CurrentUser.require(), id, body.roles());
    }

    @PostMapping("/users/{id}/invite")
    @PreAuthorize("hasAuthority('users:manage')")
    public Invite reissueInvite(@PathVariable @Size(max = 26) String id) {
        return users.reissueInvite(CurrentUser.require(), id);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('roles:read')")
    public List<RoleView> roles() {
        return users.roles();
    }
}
