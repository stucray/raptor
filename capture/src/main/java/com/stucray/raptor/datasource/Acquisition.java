package com.stucray.raptor.datasource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Marks the database identity that owns {@code raw}, {@code query} and
 * {@code batch}, and everything wired to it.
 *
 * <p>Everything unqualified gets the read-only identity, which is refused on
 * {@code raw}. That is the direction the default has to point: a component that
 * forgets this annotation fails loudly on its first statement, rather than
 * quietly acquiring the ability to write the system of record.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface Acquisition {
}
