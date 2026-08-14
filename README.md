<div align="center">
  <h1>🦖 Bedrock Java</h1>
  <p><b>Learn to walk on the JVM before piloting spaceships like Spring and Quarkus.</b></p>
  
  [![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://jdk.java.net/21/)
  [![Zero Dependencies](https://img.shields.io/badge/Dependencies-0-success.svg)](#)
  [![Virtual Threads](https://img.shields.io/badge/Project-Loom-orange.svg)](#)
</div>

---

## 📖 About Bedrock

Bedrock Java is a minimalist, educational web framework built from scratch in **Java 21**, designed to demystify how modern enterprise frameworks work under the hood. 

We strictly enforce **Zero runtime dependencies**. No Spring, no Tomcat, no Netty, no Jackson, no SLF4J. Everything you see is built using fundamental Java standard library building blocks. If you master Bedrock, you master the JVM.

### ✨ GitHub Pages & Interactive Documentation
Bedrock comes with a built-in Interactive Playground and Landing Page located in the `docs/` folder.
**To host it on GitHub Pages:**
1. Go to your repository **Settings** > **Pages**.
2. Set the source to **Deploy from a branch**.
3. Select your `main` branch and the `/docs` folder.
4. Save! Your interactive documentation is now live and accessible to the world.

---

## 🚀 Getting Started

### 1. Build the Framework
Clone the repository and install it locally using Maven:
```bash
mvn clean install
```

### 2. Create your App
Add the core dependency to your project's `pom.xml`:
```xml
<dependency>
    <groupId>io.github.ronerjr</groupId>
    <artifactId>bedrock-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 3. Write Code
Bedrock uses Constructor Injection and the Reflection API to make your life easy, just like the big frameworks, but without the opaque magic.

**The Service (Business Logic):**
```java
@BedrockComponent
public class UserService {
    public UserResponse findById(String id) {
        return new UserResponse(id, "Ada Lovelace", "JVM Expert");
    }
}
```

**The Controller (Web Layer):**
```java
@BedrockController
public class UserController {
    
    // Constructor Injection automatically resolved by Bedrock!
    private final UserService userService;
    
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @BedrockGet("/api/users/{id}")
    public void getUser(Context ctx) {
        String id = ctx.pathParam("id");
        ctx.ok(userService.findById(id));
    }
}
```

**The Application (Entrypoint):**
```java
public class Application {
    public static void main(String[] args) {
        BedrockApp.create(8080)
            .before(ctx -> BedrockLogger.info("HTTP", "Incoming request: " + ctx.path()))
            .bindControllers(UserService.class, UserController.class)
            .start();
    }
}
```

### 4. Run & Test!
Run your `Application` main method from your IDE or via terminal. Bedrock will boot instantly using Virtual Threads.

Visit the built-in Developer Experience UI on your browser:
🔗 **http://localhost:8080/bedrock/ui**

There, you can inspect all your registered routes and send test HTTP requests seamlessly!

### 5. See it in Action (The Request Lifecycle)
When you run the application and make a GET request to `/api/users/1`, here is the beautiful output you will see in your terminal. Notice how the IoC maps the beans, the Router registers the paths, and the `Before Middleware` intercepts the request:

```text
[INFO] [ROUTER] Route registered: GET /api/ping
[INFO] [BEDROCK-IOC] Mapped bean: 'UserService'
[INFO] [BEDROCK-IOC] Mapped bean: 'UserController'
[INFO] [ROUTER] Route registered: GET /api/users/{id}
 ____           _                _
|  _ \         | |              | |
| |_) | ___  __| |_ __ ___   ___| | __
|  _ < / _ \/ _` | '__/ _ \ / __| |/ /
| |_) |  __/ (_| | | | (_) | (__|   <
|____/ \___|\__,_|_|  \___/ \___|_|\_\

[INFO] [SYSTEM] 🦖 Bedrock Java Framework running at full speed on Virtual Threads!
[INFO] [SYSTEM] 🔗 Playground UI: http://localhost:8080/bedrock/ui
---------------------------------------------------------
[LOG] 🪵 Request intercepted at: /api/users/1
```

And the client seamlessly receives the JSON response with the headers injected by the `After Middleware`:

```http
HTTP/1.1 200 OK
X-powered-by: Bedrock-Java-21
Content-type: application/json; charset=UTF-8

{
  "id": "1",
  "name": "Ada Lovelace",
  "level": "JVM Expert"
}
```

---

## 🧠 Architectural Masterpieces (What you will learn)

### 🧬 Graph-Based Dependency Injection
The IoC (`BedrockContainer`) resolves dependencies recursively using Constructor Injection. It enforces immutability and features a cognitive shield: **Circular Dependency Detection**. If your beans depend on each other infinitely, Bedrock catches it and prevents a `StackOverflowError`.

### 🛡️ State/Buffer Pattern & AfterMiddlewares
The HTTP `Context` does not write directly to the network when you call `ctx.ok()`. It buffers the state. This allows powerful `AfterMiddlewares` to intercept the response and inject global headers (like CORS or `X-Powered-By`) right before flushing the bytes to the client.

### 🧵 Pure Virtual Threads (Project Loom)
No thread pools, no OS blocking. The internal HTTP server (`com.sun.net.httpserver.HttpServer`) is natively coupled with `Executors.newVirtualThreadPerTaskExecutor()`. Every request gets its own featherweight Virtual Thread.

### 💖 Actionable Exceptions (Developer Experience)
Tired of unreadable stack traces? Bedrock exceptions are inspired by Spring's `FailureAnalyzer`. When things break, the framework prints a beautiful ASCII banner explaining the **Reason** and providing a clear **Action** to fix it.

```text
**************************************************************
🦖 BEDROCK FATAL ERROR: Framework execution halted
**************************************************************
[Reason]: Could not resolve dependency 'UserService'.

[Action Required]: 
Ensure that 'UserService' is passed to app.bindControllers(...)
**************************************************************
```

---

## 🤝 Contributing
Want to help build the best educational framework in the world? Read our [CONTRIBUTING.md](CONTRIBUTING.md) guide!
