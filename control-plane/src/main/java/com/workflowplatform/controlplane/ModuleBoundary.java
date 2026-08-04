package com.workflowplatform.controlplane;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a top-level control-plane module and its allowed compile-time dependencies. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PACKAGE)
public @interface ModuleBoundary {

    /** Stable logical module name used in architecture rules and documentation. */
    String value();

    /** Logical module names this module may depend on through their root-package API. */
    String[] allowedDependencies() default {};
}
