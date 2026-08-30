package com.ems.identity_service.service.impl;

import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RefreshTokenRequest;
import com.ems.identity_service.dto.request.RegisterRequest;
import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.dto.response.TokenValidationResponse;
import com.ems.identity_service.entity.RoleEntity;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.entity.UserRoles;
import com.ems.identity_service.exception.AccountBannedException;
import com.ems.identity_service.exception.InvalidTokenException;
import com.ems.identity_service.repository.RoleRepository;
import com.ems.identity_service.repository.UserRepository;
import com.ems.identity_service.repository.UserRolesRepository;
import com.ems.identity_service.security.JwtService;
import com.ems.identity_service.service.AuthService;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRolesRepository userRolesRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Override
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use");
        }

        // Create new user
        UserEntity user = UserEntity.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .isBanned(false)
                .createdAt(LocalDateTime.now())
                .build();

        UserEntity savedUser = userRepository.save(user);

        // Assign roles if provided
        if (request.getRoles() != null && !request.getRoles().isEmpty()) {
            List<UserRoles> userRoles = request.getRoles().stream()
                    .map(roleName -> {
                        RoleEntity role = roleRepository
                                .findByRoleName(roleName)
                                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
                        return UserRoles.builder()
                                .user(savedUser)
                                .role(role)
                                .assignedAt(LocalDateTime.now())
                                .build();
                    })
                    .collect(Collectors.toList());

            userRolesRepository.saveAll(userRoles);
            savedUser.setUserRoles(userRoles);
        }

        String token = jwtService.generateToken(savedUser);
        String refreshToken = jwtService.generateRefreshToken(savedUser);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(savedUser.getUserId())
                .email(savedUser.getEmail())
                .username(savedUser.getUsername())
                .departmentId(
                        savedUser.getDepartment() != null
                                ? savedUser.getDepartment().getDepartmentId()
                                : null)
                .departmentName(
                        savedUser.getDepartment() != null
                                ? savedUser.getDepartment().getDepartmentName()
                                : null)
                .roles(
                        savedUser.getUserRoles() != null
                                ? savedUser.getUserRoles().stream()
                                        .map(ur -> ur.getRole().getRoleName())
                                        .collect(Collectors.toList())
                                : List.of())
                .isBanned(savedUser.getIsBanned())
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), request.getPassword()));
        } catch (AuthenticationException e) {
            throw new IllegalArgumentException("Email or password is incorrect");
        }

        // Check if user is banned
        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        // Load user roles
        List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());
        user.setUserRoles(userRoles);

        String token = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .departmentId(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null)
                .departmentName(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentName() : null)
                .roles(userRoles.stream().map(ur -> ur.getRole().getRoleName()).collect(Collectors.toList()))
                .isBanned(user.getIsBanned())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalArgumentException("User not authenticated");
        }

        UserEntity user = userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        // Load user roles
        List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());

        return AuthResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .departmentId(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null)
                .departmentName(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentName() : null)
                .roles(userRoles.stream().map(ur -> ur.getRole().getRoleName()).collect(Collectors.toList()))
                .isBanned(user.getIsBanned())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TokenValidationResponse validateToken(String token) {
        String jwt = extractRawToken(token);

        try {
            Long userId = jwtService.extractUserId(jwt);
            List<String> roles = jwtService.extractRoles(jwt);

            if (userId == null) {
                throw new InvalidTokenException("Invalid token: missing user ID claim");
            }

            return TokenValidationResponse.builder().userId(userId).roles(roles).build();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid or expired token");
        }
    }

    private String extractRawToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is required");
        }

        if (token.startsWith("Bearer ")) {
            String bearerToken = token.substring(7);
            if (bearerToken.isBlank()) {
                throw new InvalidTokenException("Token is required");
            }
            return bearerToken;
        }

        return token;
    }

    @Override
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String requestRefreshToken = request.getRefreshToken();
        String username = jwtService.extractUsername(requestRefreshToken);

        if (username != null) {
            UserEntity user = userRepository
                    .findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // Check if token is valid
            if (jwtService.isTokenValid(requestRefreshToken, user)) {

                // Optional: Check if banned
                if (user.getIsBanned()) {
                    throw new AccountBannedException("Your account has been banned from the system");
                }

                // Generate new access token
                String token = jwtService.generateToken(user);

                // Load roles
                List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());

                return AuthResponse.builder()
                        .token(token)
                        .refreshToken(requestRefreshToken) // Return the same refresh token, or generate a new one
                        .userId(user.getUserId())
                        .email(user.getEmail())
                        .username(user.getUsername())
                        .departmentId(
                                user.getDepartment() != null
                                        ? user.getDepartment().getDepartmentId()
                                        : null)
                        .departmentName(
                                user.getDepartment() != null
                                        ? user.getDepartment().getDepartmentName()
                                        : null)
                        .roles(userRoles.stream()
                                .map(ur -> ur.getRole().getRoleName())
                                .collect(Collectors.toList()))
                        .isBanned(user.getIsBanned())
                        .build();
            }
        }
        throw new InvalidTokenException("Invalid refresh token");
    }
}
