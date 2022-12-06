package com.minwonhaeso.esc.member.service;


import com.minwonhaeso.esc.component.MailComponents;
import com.minwonhaeso.esc.error.exception.AuthException;
import com.minwonhaeso.esc.error.type.AuthErrorCode;
import com.minwonhaeso.esc.member.model.dto.*;
import com.minwonhaeso.esc.member.model.entity.Member;
import com.minwonhaeso.esc.member.model.entity.MemberEmail;
import com.minwonhaeso.esc.member.repository.MemberEmailRepository;
import com.minwonhaeso.esc.member.repository.MemberRepository;
import com.minwonhaeso.esc.security.auth.AuthUtil;
import com.minwonhaeso.esc.security.auth.jwt.JwtExpirationEnums;
import com.minwonhaeso.esc.security.auth.jwt.JwtTokenUtil;
import com.minwonhaeso.esc.security.auth.redis.LogoutAccessToken;
import com.minwonhaeso.esc.security.auth.redis.LogoutAccessTokenRedisRepository;
import com.minwonhaeso.esc.security.auth.redis.RefreshToken;
import com.minwonhaeso.esc.security.auth.redis.RefreshTokenRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.minwonhaeso.esc.security.auth.jwt.JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME;

@RequiredArgsConstructor
@Service
public class MemberService {


    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailComponents mailComponents;
    private final MemberEmailRepository memberEmailRepository;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final LogoutAccessTokenRedisRepository logoutAccessTokenRedisRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final AuthUtil authUtil;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public SignDto.Response signUser(SignDto.Request signDto) {
        Optional<MemberEmail> memberEmail = memberEmailRepository.findById(signDto.getKey());
        if (memberEmail.isEmpty()) {
            throw new AuthException(AuthErrorCode.EmailAuthNotYet);
        }
        memberEmailRepository.delete(memberEmail.get());
        signDto.setPassword(passwordEncoder.encode(signDto.getPassword()));
        Member member = Member.of(signDto);
        memberRepository.save(member);
        return SignDto.Response.builder()
                .name(member.getName())
                .image(member.getImgUrl())
                .build();
    }

    @Transactional(readOnly = true)
    public void emailDuplicateYn(String email) {
        Optional<Member> optional = memberRepository.findByEmail(email);
        if (optional.isPresent()) {
            throw new AuthException(AuthErrorCode.EmailAlreadySignUp);
        }
    }

    public String deliverEmailAuthCode(String email) {
        String uuid = authUtil.generateAuthNo();
        Long emailExpiredTime = 1000L * 60 * 60 * 2;
        MemberEmail memberEmail = MemberEmail.createEmailAuthKey(email, uuid, emailExpiredTime);
        String subject = "[ESC] 이메일 인증 안내";
        String content = "<p>이메일 인증 코드 : " + uuid + "</p>";
        mailComponents.sendMail(email, subject, content);
        memberEmailRepository.save(memberEmail);
        return memberEmail.getId();
    }

    public void emailAuthentication(String key) {
        MemberEmail memberEmail = memberEmailRepository.findById(key).orElseThrow(
                () -> new AuthException(AuthErrorCode.EmailAuthTimeOut));
        memberEmailRepository.save(memberEmail);
        if (!memberEmail.getId().equals(key)) {
            throw new AuthException(AuthErrorCode.AuthKeyNotMatch);
        }
    }

    @Transactional
    public LoginDto.Response login(LoginDto.Request loginDto) {
        Member member = memberRepository.findByEmail(loginDto.getEmail()).
                orElseThrow(() -> new AuthException(AuthErrorCode.MemberNotFound));
        checkPassword(loginDto.getPassword(), member.getPassword());
        String email = member.getEmail();
        String accessToken = jwtTokenUtil.generateAccessToken(email);
        RefreshToken refreshToken = saveRefreshToken(email);
        return LoginDto.Response.of(email, member.getImgUrl(), accessToken, refreshToken.getRefreshToken());
    }

    private RefreshToken saveRefreshToken(String username) {
        return refreshTokenRedisRepository.save(RefreshToken.createRefreshToken(username,
                jwtTokenUtil.generateRefreshToken(username), REFRESH_TOKEN_EXPIRATION_TIME.getValue()));
    }

    private void checkPassword(String rawPassword, String findMemberPassword) {
        if (!passwordEncoder.matches(rawPassword, findMemberPassword)) {
            throw new AuthException(AuthErrorCode.PasswordNotEqual);
        }
    }

    public void logout(TokenDto tokenDto, String username) {
        String accessToken = resolveToken(tokenDto.getAccessToken());
        long remainMilliSeconds = jwtTokenUtil.getRemainMilliSeconds(accessToken);
        refreshTokenRedisRepository.deleteById(username);
        logoutAccessTokenRedisRepository.save(LogoutAccessToken.of(accessToken, username, remainMilliSeconds));
    }


    public TokenDto reissue(String refreshToken) {
        refreshToken = resolveToken(refreshToken);
        String username = getCurrentUsername();
        RefreshToken redisRefreshToken = refreshTokenRedisRepository.findById(username).orElseThrow(
                () -> new AuthException(AuthErrorCode.AccessTokenAlreadyExpired));
        if (refreshToken.equals(redisRefreshToken.getRefreshToken())) {
            return reissueRefreshToken(refreshToken, username);
        }
        throw new AuthException(AuthErrorCode.TokenNotMatch);
    }

    private TokenDto reissueRefreshToken(String refreshToken, String username) {
        if (lessThanReissueExpirationTimesLeft(refreshToken)) {
            String accessToken = jwtTokenUtil.generateAccessToken(username);
            return TokenDto.of(accessToken, saveRefreshToken(username).getRefreshToken());
        }
        return TokenDto.of(jwtTokenUtil.generateAccessToken(username), refreshToken);
    }

    public InfoDto.Response info(UserDetails user) {
        if (user.getUsername() == null) {
            throw new AuthException(AuthErrorCode.MemberNotLogIn);
        }
        Member member = memberRepository.findByEmail(user.getUsername())
                .orElseThrow(() -> new AuthException(AuthErrorCode.MemberNotLogIn));
        return InfoDto.Response.builder()
                .name(member.getName())
                .email(member.getEmail())
                .password(member.getPassword())
                .imgUrl(member.getImgUrl())
                .build();
    }

    public PatchInfo.Request patchInfo(UserDetails user, PatchInfo.Request request) {
        Member member = memberRepository.findByEmail(user.getUsername())
                .orElseThrow(() -> new AuthException(AuthErrorCode.MemberNotLogIn));
        if (request.getNickname() != null) {
            member.setNickname(request.getNickname());
        } else if (request.getImgUrl() != null) {
            member.setImgUrl(request.getImgUrl());
        }
        memberRepository.save(member);
        return request;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deleteMember(UserDetails user) {
        Member member = memberRepository.findByEmail(user.getUsername())
                .orElseThrow(() -> new AuthException(AuthErrorCode.MemberNotLogIn));
        memberRepository.delete(member);
    }

    public String resolveToken(String token) {
        return token.substring(7);
    }

    private boolean lessThanReissueExpirationTimesLeft(String refreshToken) {
        return jwtTokenUtil.getRemainMilliSeconds(refreshToken) < JwtExpirationEnums.REISSUE_EXPIRATION_TIME.getValue();
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails principal = (UserDetails) authentication.getPrincipal();
        return principal.getUsername();
    }

    public String changePasswordMail(String email) {
        String uuid = authUtil.generateAuthNo();
        Long emailExpiredTime = 1000L * 60 * 60 * 2;
        MemberEmail memberEmail = MemberEmail.createEmailAuthKey(email, uuid, emailExpiredTime);
        String subject = "[ESC] 비밀번호 변경 안내";
        String content = "<p>비밀번호 변경 코드: " + uuid + "</p>";
        mailComponents.sendMail(email, subject, content);
        memberEmailRepository.save(memberEmail);
        return uuid;
    }

    public void changePasswordMailAuth(String key) {
        MemberEmail memberEmail = memberEmailRepository.findById(key).orElseThrow(
                () -> new AuthException(AuthErrorCode.EmailAuthTimeOut));
        memberEmailRepository.delete(memberEmail);
        if (!memberEmail.getId().equals(key)) {
            throw new AuthException(AuthErrorCode.AuthKeyNotMatch);
        }
    }

    public void changePassword(CPasswordDto.Request request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AuthException(AuthErrorCode.EmailNotMatched));
        boolean match = passwordEncoder.matches(request.getPrePassword(), member.getPassword());
        if (!match) {
            throw new AuthException(AuthErrorCode.PasswordNotEqual);
        }
        if (!request.getConfirmPassword().equals(request.getNewPassword())) {
            throw new AuthException(AuthErrorCode.PasswordNotEqual);
        }
        member.setPassword(passwordEncoder.encode(request.getNewPassword()));
        memberRepository.save(member);
    }
}
