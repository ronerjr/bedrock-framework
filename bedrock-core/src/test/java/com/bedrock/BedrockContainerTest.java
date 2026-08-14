package com.bedrock;

import com.bedrock.exception.BedrockException;
import com.bedrock.ioc.BedrockComponent;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.ioc.BedrockInject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockContainerTest {

    @BedrockComponent
    static class ValidService {
        public String sayHello() { return "Hello!"; }
    }

    @BedrockComponent
    static class ValidController {
        final ValidService service;
        
        // Constructor Injection
        @BedrockInject
        public ValidController(ValidService service) {
            this.service = service;
        }
    }

    @BedrockComponent
    static class MissingDependencyController {
        public MissingDependencyController(UnregisteredService service) {}
    }

    static class UnregisteredService {}

    // --- Circular Dependency Scenario ---
    @BedrockComponent
    static class BeanA {
        public BeanA(BeanB b) {}
    }

    @BedrockComponent
    static class BeanB {
        public BeanB(BeanC c) {}
    }

    @BedrockComponent
    static class BeanC {
        public BeanC(BeanA a) {}
    }

    @Test
    void shouldRegisterAndInjectDependenciesCorrectly() {
        BedrockContainer container = new BedrockContainer();
        
        // Registering
        assertDoesNotThrow(() -> container.register(ValidService.class, ValidController.class));
        
        // Retrieval
        ValidController controller = container.getBean(ValidController.class);
        assertNotNull(controller);
        assertNotNull(controller.service);
        assertEquals("Hello!", controller.service.sayHello());
    }

    @Test
    void shouldThrowExceptionWhenDependencyIsMissing() {
        BedrockContainer container = new BedrockContainer();
        
        BedrockException exception = assertThrows(BedrockException.class, () -> {
            container.register(MissingDependencyController.class);
        });
        
        assertTrue(exception.getMessage().contains("Could not resolve dependency 'UnregisteredService'"));
    }

    @Test
    void shouldDetectCircularDependencyAndThrowException() {
        BedrockContainer container = new BedrockContainer();
        
        BedrockException exception = assertThrows(BedrockException.class, () -> {
            container.register(BeanA.class, BeanB.class, BeanC.class);
        });
        
        assertTrue(exception.getMessage().contains("Circular dependency detected!"));
    }
}
