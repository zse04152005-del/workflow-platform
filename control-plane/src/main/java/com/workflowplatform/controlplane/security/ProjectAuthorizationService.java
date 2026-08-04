package com.workflowplatform.controlplane.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Minimum project permission matrix; platform administration is intentionally absent. */
public final class ProjectAuthorizationService {

    private static final Map<Role, Set<Permission>> MATRIX = createPermissionMatrix();

    public void require(Role role, Permission permission) {
        if (!MATRIX.getOrDefault(role, Set.of()).contains(permission)) {
            throw new SecurityException("project permission denied");
        }
    }

    private static Map<Role, Set<Permission>> createPermissionMatrix() {
        Map<Role, Set<Permission>> values = new EnumMap<>(Role.class);
        values.put(Role.VIEWER, Set.of(Permission.READ));
        values.put(
                Role.OPERATOR,
                Set.of(
                        Permission.READ,
                        Permission.CONTROL_INSTANCE,
                        Permission.HANDLE_DEAD_LETTER));
        values.put(
                Role.DEVELOPER,
                Set.of(Permission.READ, Permission.EDIT_WORKFLOW, Permission.PUBLISH_WORKFLOW));
        values.put(Role.PROJECT_ADMIN, java.util.EnumSet.allOf(Permission.class));
        return Map.copyOf(values);
    }

    public enum Role {
        VIEWER,
        OPERATOR,
        DEVELOPER,
        PROJECT_ADMIN
    }

    public enum Permission {
        READ,
        EDIT_WORKFLOW,
        PUBLISH_WORKFLOW,
        CONTROL_INSTANCE,
        HANDLE_DEAD_LETTER,
        MANAGE_MEMBERS,
        MANAGE_CONNECTORS
    }
}
