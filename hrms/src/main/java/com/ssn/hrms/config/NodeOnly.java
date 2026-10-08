package com.ssn.hrms.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Bean is created only when the jar runs as an HR node (hrms.role=node, the default). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "hrms", name = "role", havingValue = "node", matchIfMissing = true)
public @interface NodeOnly {
}
