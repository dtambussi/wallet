package com.wallet.application.service;

import com.wallet.application.port.out.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserQueryService {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean existsById(UUID userId) {
        return userRepository.existsById(userId);
    }
}
