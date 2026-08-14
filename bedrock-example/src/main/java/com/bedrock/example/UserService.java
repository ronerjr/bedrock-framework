package com.bedrock.example;

import com.bedrock.ioc.BedrockComponent;

/**
 * 🎓 BEDROCK TUTORIAL: The Service Layer (Business Logic)
 * 
 * The @BedrockComponent annotation registers this class in the Inversion of Control (IoC) Container.
 * This means Bedrock will create a SINGLE instance (Singleton) of this class at startup,
 * and reuse it whenever another component (like UserController) asks for it.
 */
@BedrockComponent
public class UserService {

    public UserResponse findById(String id) {
        // Simulating a database fetch
        if (id.equals("1")) {
            return new UserResponse(id, "Ada Lovelace", "JVM Expert");
        } else if (id.equals("2")) {
            return new UserResponse(id, "Alan Turing", "Algorithm Master");
        }
        return null;
    }
}
