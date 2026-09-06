package com.bedrock.example;

import com.bedrock.ioc.BedrockComponent;
import com.bedrock.ioc.BedrockInject;

import java.util.List;

/**
 * 🎓 BEDROCK TUTORIAL: The Service Layer (Business Logic)
 * 
 * Notice: UserService now injects 'IUserRepository' instead of maintaining an in-memory map!
 * 
 * ARCHITECTURAL FLOW:
 * Client ➡️ HTTP Request ➡️ UserController ➡️ IUserService (UserService) ➡️ IUserRepository (SqliteUserRepository) ➡️ BedrockJdbc ➡️ SQLite DB
 * 
 * Every layer is strictly isolated behind interfaces (SOLID 'D').
 */
@BedrockComponent
public class UserService implements IUserService {

    private final IUserRepository userRepository;

    @BedrockInject
    public UserService(IUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<UserResponse> findAll() {
        return userRepository.findAll();
    }

    @Override
    public UserResponse findById(String id) {
        return userRepository.findById(id).orElse(null);
    }

    @Override
    public UserResponse create(CreateUserRequest request) {
        return userRepository.save(request);
    }

    @Override
    public UserResponse update(String id, UpdateUserRequest request) {
        return userRepository.update(id, request).orElse(null);
    }

    @Override
    public boolean delete(String id) {
        return userRepository.deleteById(id);
    }
}
