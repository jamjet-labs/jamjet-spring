package dev.jamjet.spring.test.annotations;

import dev.jamjet.spring.test.JamjetReplayExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(JamjetReplayExtension.class)
public @interface WithJamjetRuntime {
    String image() default "ghcr.io/jamjet-labs/jamjet";
    String tag() default "0.3.1";
}
