package com.vibestory.team;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class TeamController {
    private final TeamService teams;

    public TeamController(TeamService teams) { this.teams = teams; }

    public record TeamName(@NotBlank @Size(max = 120) String name) {}
    public record TeamRoleChange(@NotNull TeamRole role) {}
    public record TeamInvite(@NotBlank @Email @Size(max = 254) String email, @NotNull TeamRole role) {}

    @GetMapping("/api/teams")
    public List<TeamService.TeamView> list(@AuthenticationPrincipal Viewer viewer) { return teams.list(viewer.id()); }

    @PostMapping("/api/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamService.TeamView create(@AuthenticationPrincipal Viewer viewer, @Valid @RequestBody TeamName body) {
        return teams.create(viewer.id(), body.name());
    }

    @GetMapping("/api/teams/{teamId}")
    public TeamService.TeamView get(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId) {
        return teams.get(viewer.id(), teamId);
    }

    @PatchMapping("/api/teams/{teamId}")
    public TeamService.TeamView rename(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId, @Valid @RequestBody TeamName body) {
        return teams.rename(viewer.id(), teamId, body.name());
    }

    @GetMapping("/api/teams/{teamId}/members")
    public List<TeamService.MemberView> members(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId) {
        return teams.members(viewer.id(), teamId);
    }

    @PatchMapping("/api/teams/{teamId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId, @PathVariable long userId,
                           @Valid @RequestBody TeamRoleChange body) {
        teams.changeRole(viewer.id(), teamId, userId, body.role());
    }

    @DeleteMapping("/api/teams/{teamId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId, @PathVariable long userId) {
        teams.remove(viewer.id(), teamId, userId);
    }

    @GetMapping("/api/teams/{teamId}/invitations")
    public List<TeamService.InvitationView> invitations(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId) {
        return teams.invitations(viewer.id(), teamId);
    }

    @PostMapping("/api/teams/{teamId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamService.InvitationView invite(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId,
                                             @Valid @RequestBody TeamInvite body) {
        return teams.invite(viewer.id(), teamId, body.email(), body.role());
    }

    @DeleteMapping("/api/teams/{teamId}/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId, @PathVariable long id) {
        teams.revoke(viewer.id(), teamId, id);
    }

    @GetMapping("/api/me/team-invitations")
    public List<TeamService.InvitationView> inbox(@AuthenticationPrincipal Viewer viewer) { return teams.inbox(viewer); }

    @PostMapping("/api/me/team-invitations/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) { teams.respond(viewer, id, true); }

    @PostMapping("/api/me/team-invitations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) { teams.respond(viewer, id, false); }
}
