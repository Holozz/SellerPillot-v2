package com.sellerpilot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import com.sellerpilot.common.ApiException;

@Service
public class JwtService {
    private final byte[] secret;
    private final JsonMapper json;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    public JwtService(@Value("${sellerpilot.jwt-secret}")String key,JsonMapper json){
        this.secret=key.getBytes(StandardCharsets.UTF_8);this.json=json;
        if(secret.length<32)throw new IllegalArgumentException("JAVA_JWT_SECRET 至少需要32字节");
    }
    private byte[] sign(String input){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public String issue(long user){
        String header=ENCODER.encodeToString(json.writeValueAsBytes(Map.of("alg","HS256","typ","JWT")));
        String payload=ENCODER.encodeToString(json.writeValueAsBytes(Map.of("sub",Long.toString(user),"exp",Instant.now().plusSeconds(3600).getEpochSecond())));
        return header+"."+payload+"."+ENCODER.encodeToString(sign(header+"."+payload));
    }
    public long verify(String token){
        try{
            String[] parts=token.split("\\.",-1);
            if(parts.length!=3||token.length()>4096)throw new IllegalArgumentException();
            Map<?,?> header=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Map.class);
            if(!"HS256".equals(header.get("alg"))||!MessageDigest.isEqual(sign(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))throw new IllegalArgumentException();
            Map<?,?> payload=json.readValue(Base64.getUrlDecoder().decode(parts[1]),Map.class);
            if(!(payload.get("sub") instanceof String sub)||!(payload.get("exp") instanceof Number exp)||exp.longValue()<=Instant.now().getEpochSecond())throw new IllegalArgumentException();
            long user=Long.parseLong(sub);if(user<1)throw new IllegalArgumentException();return user;
        }catch(RuntimeException e){throw new ApiException(401,"登录凭证无效或已过期");}
    }
}
