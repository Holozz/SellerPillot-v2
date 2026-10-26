package com.sellerpilot.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import com.sellerpilot.common.ApiException;

@Configuration
public class SecurityConfig {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noDefaultPassword(){
        return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("仅支持JWT身份");};
    }
    @Bean PasswordEncoder passwordEncoder(){return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();}
    @Bean SecurityFilterChain filterChain(HttpSecurity http,JwtService tokens,JsonMapper json)throws Exception{
        OncePerRequestFilter jwt=new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
                String header=request.getHeader("Authorization");
                if(header!=null){
                    try{
                        if(!header.startsWith("Bearer "))throw new ApiException(401,"登录凭证无效");
                        long uid=tokens.verify(header.substring(7));
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(uid,null,List.of()));
                    }catch(ApiException e){response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(json.writeValueAsString(Map.of("detail",e.detail)));return;}
                }
                chain.doFilter(request,response);
            }
        };
        return http.csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth.requestMatchers("/auth/login","/auth/register","/api/auth/login","/api/auth/register","/actuator/health","/actuator/prometheus","/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,e)->{
                response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"detail\":\"请先登录\"}");
            }))
            .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();
    }
}
