package com.vibestory.team;

@io.swagger.v3.oas.annotations.media.Schema(enumAsRef = true)
public enum TeamRole {
    OWNER, ADMIN, MEMBER, VIEWER;

    public boolean managesTeam() {
        return this == OWNER || this == ADMIN;
    }

    public boolean editsProject() {
        return this != VIEWER;
    }

    boolean manages(TeamRole target) {
        return target != OWNER && (this == OWNER || this == ADMIN && (target == MEMBER || target == VIEWER));
    }
}
