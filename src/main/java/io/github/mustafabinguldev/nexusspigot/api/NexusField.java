package io.github.mustafabinguldev.nexusspigot.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field of a data model as synchronized with NexusCore. The field name
 * must match the field name of the addon on the NexusCore side.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NexusField {

    /** Exactly one field per model must be the id (the cache key). */
    boolean id() default false;

    /** Used when the value is missing. Plain text for simple types, JSON for complex ones. */
    String defaultValue() default "";
}
