package com.workflowplatform.controlplane.runtime;

import com.workflowplatform.controlplane.definition.internal.DefinitionRepository;

/** Intentional test-only architecture violation. */
public final class RuntimeToDefinitionRepositoryViolation {

    private final DefinitionRepository definitionDependency;

    public RuntimeToDefinitionRepositoryViolation(DefinitionRepository definitionRepository) {
        this.definitionDependency = definitionRepository;
    }

    public DefinitionRepository definitionRepository() {
        return definitionDependency;
    }
}
