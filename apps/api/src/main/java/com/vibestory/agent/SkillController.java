package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
class SkillController {
    public record SaveSkill(@NotNull @Size(max=65536) String document, @NotNull @Size(max=64) String expectedVersion) {}
    private final SkillDirectory skills;
    SkillController(SkillDirectory skills) { this.skills=skills; }
    @GetMapping
    List<SkillDirectory.Entry> list(@AuthenticationPrincipal Viewer user,@RequestParam(required=false) String role) {
        return skills.list(user.id(),role);
    }
    @GetMapping("/{id}")
    SkillDirectory.Skill read(@AuthenticationPrincipal Viewer user,@PathVariable String id) { return skills.read(user.id(),id); }
    @PutMapping("/{id}")
    SkillDirectory.Skill save(@AuthenticationPrincipal Viewer user,@PathVariable String id,@Valid @RequestBody SaveSkill input) {
        return skills.save(user.id(),id,input.expectedVersion(),input.document());
    }
}
