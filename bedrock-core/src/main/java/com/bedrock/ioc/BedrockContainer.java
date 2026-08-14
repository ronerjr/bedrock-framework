package com.bedrock.ioc;

import com.bedrock.core.BedrockLogger;
import com.bedrock.exception.BedrockException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart of the Bedrock Java Inversion of Control (IoC) Engine.
 * 
 * DESIGN PATTERN: Singleton Registry & Factory
 * Manages the lifecycle of beans as singletons.
 * Resolves dependencies recursively via Constructor Injection, ensuring immutability.
 */
public class BedrockContainer {
    
    private final ConcurrentHashMap<Class<?>, Object> beans = new ConcurrentHashMap<>();
    private final Set<Class<?>> registeredClasses = new HashSet<>();

    /**
     * Registers and instantiates classes, resolving their internal dependencies.
     */
    public void register(Class<?>... classes) {
        registeredClasses.addAll(Arrays.asList(classes));
        
        for (Class<?> clazz : classes) {
            resolveAndInstantiate(clazz, new HashSet<>());
        }
    }

    /**
     * Resolves a dependency recursively using Graph Traversal.
     * Uses a 'resolving' set to detect Circular Dependencies and prevent StackOverflowError.
     */
    private Object resolveAndInstantiate(Class<?> clazz, Set<Class<?>> resolving) {
        // 1. Must be a registered class to be managed by the IoC
        if (!registeredClasses.contains(clazz)) {
            throw new BedrockException(
                "Could not resolve dependency '" + clazz.getSimpleName() + "'.",
                "Ensure that '" + clazz.getSimpleName() + "' is passed to app.bindControllers(...) in your BedrockApp startup."
            );
        }

        // 2. If already instantiated, return the singleton instance
        if (beans.containsKey(clazz)) {
            return beans.get(clazz);
        }

        // 3. Circular Dependency Detection
        if (resolving.contains(clazz)) {
            throw new BedrockException(
                "Circular dependency detected! Class '" + clazz.getSimpleName() + "' is caught in an infinite resolution loop.",
                "Review the constructors of your classes. If A depends on B, and B depends on A, Bedrock cannot instantiate them. You must redesign your architecture to break the cycle."
            );
        }

        resolving.add(clazz);

        try {
            // Find the constructor. For simplicity, we use the first declared one,
            // or the one explicitly annotated with @BedrockInject.
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (constructors.length == 0) {
                throw new BedrockException(
                    "No constructor found in '" + clazz.getSimpleName() + "'.",
                    "Add a public constructor to the class."
                );
            }
            
            Constructor<?> targetConstructor = constructors[0];
            for (Constructor<?> c : constructors) {
                if (c.isAnnotationPresent(BedrockInject.class)) {
                    targetConstructor = c;
                    break;
                }
            }

            targetConstructor.setAccessible(true);
            Parameter[] parameters = targetConstructor.getParameters();
            Object[] resolvedArgs = new Object[parameters.length];

            // 3. Resolve each constructor parameter recursively
            for (int i = 0; i < parameters.length; i++) {
                Class<?> paramType = parameters[i].getType();
                Class<?> actualTypeToResolve = paramType;
                
                // Polymorphism support: If param is an interface, find an implementation
                if (paramType.isInterface()) {
                    for (Class<?> candidate : registeredClasses) {
                        if (paramType.isAssignableFrom(candidate) && !candidate.isInterface()) {
                            actualTypeToResolve = candidate;
                            break;
                        }
                    }
                }
                
                Object dependency = resolveAndInstantiate(actualTypeToResolve, resolving);
                
                if (dependency == null) {
                    throw new BedrockException(
                        "Could not resolve dependency '" + paramType.getSimpleName() + "' required by '" + clazz.getSimpleName() + "'.",
                        "Ensure the required dependency is registered in BedrockApp. If it's an interface, ensure at least one implementation is registered."
                    );
                }
                
                resolvedArgs[i] = dependency;
            }

            // 4. Instantiate and register the bean
            Object instance = targetConstructor.newInstance(resolvedArgs);
            beans.put(clazz, instance);
            
            BedrockLogger.info("BEDROCK-IOC", "Mapped bean: '" + clazz.getSimpleName() + "'");
            
            resolving.remove(clazz);
            return instance;

        } catch (BedrockException e) {
            throw e; // Re-throw our custom exception
        } catch (Exception e) {
            throw new BedrockException(
                "Could not instantiate '" + clazz.getSimpleName() + "'.",
                "Ensure all its dependencies are registered correctly and constructors do not throw exceptions.",
                e
            );
        }
    }

    /**
     * Retrieves a Singleton already managed by the container.
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> clazz) {
        Object bean = beans.get(clazz);
        if (bean == null) {
            // Try polymorphism
            for (Object registeredBean : beans.values()) {
                if (clazz.isAssignableFrom(registeredBean.getClass())) {
                    return (T) registeredBean;
                }
            }
        }
        return (T) bean;
    }
}
