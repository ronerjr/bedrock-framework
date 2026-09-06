package com.bedrock;

import com.bedrock.core.BedrockApp;
import com.bedrock.exception.BedrockException;
import com.bedrock.ioc.BedrockComponent;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.ioc.BedrockInject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockIoCInterfaceBindingTest {

    interface GreetingService {
        String greet(String name);
    }

    @BedrockComponent
    static class EnglishGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    @BedrockComponent
    static class PortugueseGreetingService implements GreetingService {
        @Override
        public String greet(String name) {
            return "Olá, " + name + "!";
        }
    }

    @BedrockComponent
    static class GreetingController {
        final GreetingService service;

        @BedrockInject
        public GreetingController(GreetingService service) {
            this.service = service;
        }
    }

    interface UnboundService {
        void execute();
    }

    @BedrockComponent
    static class ControllerWithUnboundService {
        public ControllerWithUnboundService(UnboundService service) {}
    }

    @Test
    void shouldResolveInterfaceBindingInContainer() {
        BedrockContainer container = new BedrockContainer();
        container.bind(GreetingService.class, EnglishGreetingService.class);
        container.register(EnglishGreetingService.class, GreetingController.class);

        GreetingService service = container.getBean(GreetingService.class);
        assertNotNull(service);
        assertEquals("Hello, Bedrock!", service.greet("Bedrock"));

        GreetingController controller = container.getBean(GreetingController.class);
        assertNotNull(controller);
        assertNotNull(controller.service);
        assertEquals("Hello, World!", controller.service.greet("World"));
    }

    @Test
    void shouldAllowSwitchingInterfaceImplementation() {
        BedrockContainer container = new BedrockContainer();
        container.bind(GreetingService.class, PortugueseGreetingService.class);
        container.register(PortugueseGreetingService.class, GreetingController.class);

        GreetingService service = container.getBean(GreetingService.class);
        assertNotNull(service);
        assertEquals("Olá, Bedrock!", service.greet("Bedrock"));

        GreetingController controller = container.getBean(GreetingController.class);
        assertEquals("Olá, Carlos!", controller.service.greet("Carlos"));
    }

    @Test
    void shouldBindThroughBedrockAppFluentApi() {
        BedrockApp app = BedrockApp.create(8080)
                .bind(GreetingService.class, EnglishGreetingService.class)
                .register(EnglishGreetingService.class, GreetingController.class);

        GreetingService service = app.getContainer().getBean(GreetingService.class);
        assertNotNull(service);
        assertEquals("Hello, Developer!", service.greet("Developer"));
    }

    @Test
    void shouldThrowActionableExceptionWhenInterfaceIsNotBound() {
        BedrockContainer container = new BedrockContainer();

        BedrockException exception = assertThrows(BedrockException.class, () -> {
            container.register(ControllerWithUnboundService.class);
        });

        assertTrue(exception.getMessage().contains("Could not resolve dependency for interface 'UnboundService'"));
        assertTrue(exception.getMessage().contains("app.bind(UnboundService.class, UnboundServiceImpl.class)"));
    }
}
