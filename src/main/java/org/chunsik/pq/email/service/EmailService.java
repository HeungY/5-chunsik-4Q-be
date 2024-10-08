package org.chunsik.pq.email.service;

import io.sentry.Hint;
import io.sentry.Sentry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.chunsik.pq.email.dto.EmailConfirmRequestDTO;
import org.chunsik.pq.email.exception.InvalidEmailException;
import org.chunsik.pq.email.exception.TooManyRequestsException;
import org.chunsik.pq.email.model.EmailConfirm;
import org.chunsik.pq.email.repository.EmailConfirmRepository;
import org.chunsik.pq.email.util.AESUtil;
import org.chunsik.pq.login.exception.DuplicateEmailException;
import org.chunsik.pq.login.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

@Slf4j
@RequiredArgsConstructor
@Service
public class EmailService {

    @Value("${auth-code-expiration-millis}")
    private long authCodeExpirationMillis;

    @Value("${chunsik.domain}")
    private String cookieDomain;

    private static final int MAX_REQUESTS = 5;
    private static final int COOKIE_EXPIRATION_MINUTES = 30;
    private static final String REQUEST_COUNT_COOKIE_NAME = "requestCount";
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final RandomGenerator randomGenerator = RandomGeneratorFactory.of("Random").create();
    private final JavaMailSender mailSender;
    private final EmailConfirmRepository emailConfirmRepository;
    private final UserRepository userRepository;

    public void sendSimpleEmail(String email, HttpServletRequest request, HttpServletResponse response) {
        // 쿠키 기반 재전송 제한 체크
        Optional<Cookie> requestCountCookieOpt = getCookie(request, REQUEST_COUNT_COOKIE_NAME);

        int requestCount = 1;

        if (requestCountCookieOpt.isPresent()) {
            requestCount = Integer.parseInt(requestCountCookieOpt.get().getValue());

            if (requestCount >= MAX_REQUESTS) {
                throw new TooManyRequestsException("Too many requests.");
            } else {
                requestCount++;
            }
        }

        setCookie(response, "requestCount", String.valueOf(requestCount), COOKIE_EXPIRATION_MINUTES * 60);

        // 6자리 난수 생성
        String verificationCode = generateVerificationCode();

        // secret_code 암호화
        String encryptedCode;
        try {
            encryptedCode = AESUtil.encrypt(verificationCode);
        } catch (GeneralSecurityException e) {
            Sentry.captureException(e);
            logger.error("Error while encrypting the secret code", e);
            throw new RuntimeException("Error while encrypting the secret code");
        }

        // 이메일 인증 정보 데이터베이스에 저장 또는 업데이트
        // 동일 이메일에 대한 여러 데이터가 있으면 에러가 나서
        // 동일 이메일에 대한 데이터가 있으면 추가하지 않고 기존의 데이터를 업데이트 하는 방식으로 구현
        Optional<EmailConfirm> existingEmailConfirmOpt = emailConfirmRepository.findByEmail(email);

        EmailConfirm emailConfirm = existingEmailConfirmOpt
                .map(existingEmail -> existingEmail.toBuilder()
                        .confirmation(false)
                        .secretCode(encryptedCode)
                        .createdAt(LocalDateTime.now())
                        .isSend(true)
                        .sendedAt(LocalDateTime.now())
                        .build())
                .orElseGet(() -> EmailConfirm.builder()
                        .email(email)
                        .secretCode(encryptedCode)
                        .createdAt(LocalDateTime.now())
                        .isSend(true)
                        .sendedAt(LocalDateTime.now())
                        .build());

        emailConfirmRepository.save(emailConfirm);


        // 이메일 제목과 내용 설정
        String subject = "이메일 인증번호입니다.";
        String body = "귀하의 이메일 인증번호는 다음과 같습니다: " + verificationCode;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        message.setFrom("4Q <no-reply@qqqq.world>");

        mailSender.send(message);
    }

    private String generateVerificationCode() {
        int code = randomGenerator.nextInt(900000) + 100000; // 100000~999999 난수 생성
        return String.valueOf(code);
    }

    @Transactional
    public boolean verifyCode(EmailConfirmRequestDTO dto) {

        if (userRepository.existsByEmail(dto.getEmail()))
            throw new DuplicateEmailException("duplicated email");

        return checkEmailCode(dto.getEmail(), dto.getCode());
    }

    @Transactional
    public boolean resetCode(EmailConfirmRequestDTO dto) {

        if (!userRepository.existsByEmail(dto.getEmail()))
            throw new InvalidEmailException("email not exist");

        return checkEmailCode(dto.getEmail(), dto.getCode());
    }

    private boolean checkEmailCode(String email, String code) {
        Optional<EmailConfirm> emailConfirmOpt = emailConfirmRepository.findByEmail(email);

        if (emailConfirmOpt.isEmpty()) {
            return false;
        }
        EmailConfirm emailConfirm = emailConfirmOpt.get();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = emailConfirm.getCreatedAt();

        // 유효기간 확인
        if (Duration.between(createdAt, now).toMillis() > authCodeExpirationMillis) {
            return false;
        }

        try {
            String decryptedCode = AESUtil.decrypt(emailConfirm.getSecretCode());
            if (!decryptedCode.equals(code)) {
                return false;
            }
            EmailConfirm updatedEmailConfirm = emailConfirm.toBuilder()
                    .confirmation(true)
                    .confirmedAt(now)
                    .build();

            emailConfirmRepository.save(updatedEmailConfirm);
            return true;
        } catch (GeneralSecurityException e) {
            Sentry.captureException(e);
            logger.error("An error occurred while decrypting the secret code", e);
            throw new RuntimeException("Error while decrypting the secret code");
        }
    }


    private Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(cookie -> name.equals(cookie.getName()))
                    .findFirst();
        }
        return Optional.empty();
    }

    private void setCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(true); // XSS 공격 방지
        cookie.setDomain(cookieDomain);
        response.addCookie(cookie);
    }
}
