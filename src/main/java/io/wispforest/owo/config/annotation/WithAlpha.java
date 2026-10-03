package io.wispforest.owo.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applied to a config option of owo's Color type to indicate
 * that the config screen should expose the alpha component.
 *
 * <p>Retained for API compatibility: this reduced port does not include
 * owo-ui, so there is no config screen to consume it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface WithAlpha {}
