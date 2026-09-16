package com.bedrock.ioc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Post-Construction Initialization
 *
 * <p>Annotates a void method to be executed immediately after the IoC container
 * has fully instantiated the component and injected all of its constructor dependencies.</p>
 *
 * <h3>Why this matters in Enterprise Architecture:</h3>
 * <p>In professional frameworks, components often require initialization logic
 * (e.g. creating database tables, warming cache pools, validating configuration files).
 * Putting this logic inside constructors is an anti-pattern because constructors should only
 * assign state. If a constructor executes heavy I/O and throws an exception, the object is
 * left in a partially initialized, leak-prone state.</p>
 *
 * <p>Bedrock executes {@code @BedrockInit} methods in strict topological dependency order:
 * dependencies are initialized <i>before</i> the beans that consume them.</p>
 *
 * @see BedrockDestroy
 * @see BedrockContainer
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BedrockInit {
}
