package com.bedrock.ioc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Pre-Destruction Teardown
 *
 * <p>Annotates a void method to be executed when the IoC container is stopped
 * or closed (e.g. during application shutdown via {@code app.stop()} or {@code app.close()}).</p>
 *
 * <h3>Why this matters in Enterprise Architecture:</h3>
 * <p>Stateful resources (such as database connection pools, thread executors, file handles,
 * and network sockets) must be flushed and closed gracefully to avoid data corruption
 * or resource leaks. Bedrock executes {@code @BedrockDestroy} methods in <b>reverse</b>
 * topological order: dependents are torn down <i>before</i> the dependencies they rely on.</p>
 *
 * @see BedrockInit
 * @see BedrockContainer
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BedrockDestroy {
}
