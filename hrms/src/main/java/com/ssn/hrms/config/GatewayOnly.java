package com.ssn.hrms.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Bean is created only when the jar runs as the API gateway (hrms.role=gateway). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "hrms", name = "role", havingValue = "gateway")
public @interface GatewayOnly {
}
