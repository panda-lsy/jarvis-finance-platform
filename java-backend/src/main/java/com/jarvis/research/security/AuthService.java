package com.jarvis.research.security;

import com.jarvis.research.user.*;
import com.jarvis.research.security.AuthDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务: 注册 / 登录 / 创建模拟账户
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SimAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 注册: 创建用户 + 默认模拟盘账户(10万) */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getDisplayName() == null || req.getDisplayName().isBlank()
                        ? email : req.getDisplayName())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        user = userRepository.save(user);

        // 创建默认模拟盘账户
        SimAccount account = SimAccount.builder()
                .userId(user.getId())
                .initialCash(100000.0)
                .cash(100000.0)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtUtil.getExpiration(), toUserInfo(user, account));
    }

    /** 登录 */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("邮箱或密码错误"));
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
        SimAccount account = accountRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    SimAccount a = SimAccount.builder()
                            .userId(user.getId()).initialCash(100000.0)
                            .cash(100000.0).status("ACTIVE").createdAt(LocalDateTime.now()).build();
                    return accountRepository.save(a);
                });
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtUtil.getExpiration(), toUserInfo(user, account));
    }

    private UserInfo toUserInfo(User user, SimAccount account) {
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setEmail(user.getEmail());
        info.setDisplayName(user.getDisplayName());
        if (account != null) {
            info.setSimCash(account.getCash());
            info.setSimInitialCash(account.getInitialCash());
        }
        return info;
    }
}
