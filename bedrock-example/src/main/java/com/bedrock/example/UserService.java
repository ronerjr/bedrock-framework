package com.bedrock.example;

import com.bedrock.ioc.BedrockComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🎓 BEDROCK TUTORIAL: The Service Layer (Business Logic)
 * 
 * The @BedrockComponent annotation registers this class in the Inversion of Control (IoC) Container.
 * This means Bedrock will create a SINGLE instance (Singleton) of this class at startup,
 * and reuse it whenever another component (like UserController) asks for it.
 */
@BedrockComponent
public class UserService {

    private final Map<String, UserResponse> database = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(2);

    public UserService() {
        // Initial sample data
        database.put("1", new UserResponse("1", "Ada Lovelace", "JVM Expert"));
        database.put("2", new UserResponse("2", "Alan Turing", "Algorithm Master"));
    }

    public List<UserResponse> findAll() {
        return new ArrayList<>(database.values());
    }

    public UserResponse findById(String id) {
        return database.get(id);
    }

    public UserResponse create(CreateUserRequest request) {
        String newId = String.valueOf(idSequence.incrementAndGet());
        UserResponse newUser = new UserResponse(newId, request.name(), request.level());
        database.put(newId, newUser);
        return newUser;
    }

    public UserResponse update(String id, UpdateUserRequest request) {
        if (!database.containsKey(id)) {
            return null;
        }
        UserResponse updatedUser = new UserResponse(id, request.name(), request.level());
        database.put(id, updatedUser);
        return updatedUser;
    }

    public boolean delete(String id) {
        return database.remove(id) != null;
    }
}
