package com.sellerpilot.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;

@Service
public class AuthService {
    private final UserMapper users;private final PasswordEncoder encoder;private final JwtService tokens;
    private final ConcurrentHashMap<String,Failure> failures=new ConcurrentHashMap<>();
    private record Failure(int count,long until){}
    public AuthService(UserMapper users,PasswordEncoder encoder,JwtService tokens){this.users=users;this.encoder=encoder;this.tokens=tokens;}
    @Transactional public Map<String,Object> register(String name,String password){
        String hash=encoder.encode(password);
        users.insert(name,hash);return Map.of("id",users.lastId(),"username",name);
    }
    public Map<String,Object> login(String name,String password){
        long now=System.currentTimeMillis();Failure prior=failures.get(name);
        if(prior!=null&&prior.until>now&&prior.count>=8)throw new ApiException(429,"登录失败次数过多，请稍后重试");
        Map<String,Object> user=users.byName(name);
        if(user==null||!encoder.matches(password,Rows.text(user,"password_hash"))){
            if(failures.size()>10000){failures.entrySet().removeIf(entry->entry.getValue().until<now);if(failures.size()>10000)throw new ApiException(429,"登录请求过多");}
            failures.compute(name,(key,old)->new Failure(old!=null&&old.until>now?old.count+1:1,now+60000));
            throw new ApiException(401,"用户名或密码错误");
        }
        failures.remove(name);return Map.of("access_token",tokens.issue(Rows.number(user,"id")),"token_type","bearer");
    }
    public Map<String,Object> me(long id){Map<String,Object> user=users.byId(id);if(user==null)throw new ApiException(401,"用户不存在");return user;}
}
