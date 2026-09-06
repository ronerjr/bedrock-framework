package com.bedrock.example;

import java.util.List;

/**
 * 🎓 BEDROCK TUTORIAL: Interface Inversion (SOLID 'D')
 * 
 * Controllers depend on this interface, not the concrete implementation.
 * This decouples your web layer from storage/logic details, making unit testing
 * and swapping implementations effortless.
 */
public interface IUserService {
    List<UserResponse> findAll();
    UserResponse findById(String id);
    UserResponse create(CreateUserRequest request);
    UserResponse update(String id, UpdateUserRequest request);
    boolean delete(String id);
}
