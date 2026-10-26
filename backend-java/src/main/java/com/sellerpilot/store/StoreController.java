package com.sellerpilot.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.sellerpilot.auth.UserMapper;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;

@RestController
public class StoreController {
    private final StoreMapper stores;private final StoreAccess access;private final UserMapper users;
    public StoreController(StoreMapper stores,StoreAccess access,UserMapper users){this.stores=stores;this.access=access;this.users=users;}
    public record Create(@NotBlank @Size(max=100)String name,@Pattern(regexp="amazon|shopify") @NotNull String platform){}
    public record Member(@NotBlank String username,@Pattern(regexp="viewer|operator") @NotNull String role,Boolean active){}
    @GetMapping({"/stores","/api/stores"})public List<Map<String,Object>> list(@AuthenticationPrincipal Long user){return stores.list(user);}
    @PostMapping({"/stores","/api/stores"})@ResponseStatus(HttpStatus.CREATED)@Transactional
    public Map<String,Object> create(@AuthenticationPrincipal Long user,@Valid @RequestBody Create body){stores.insert(user,body.name,body.platform);return stores.get(stores.lastId());}
    @GetMapping({"/stores/{sid}","/api/stores/{sid}"})public Map<String,Object> get(@AuthenticationPrincipal Long user,@PathVariable long sid){return access.require(user,sid,"read",false);}
    @GetMapping("/api/v2/stores/{sid}/members")public List<Map<String,Object>> members(@AuthenticationPrincipal Long user,@PathVariable long sid){access.require(user,sid,"members",false);return stores.members(sid);}
    @PutMapping("/api/v2/stores/{sid}/members")@Transactional
    public Map<String,Object> member(@AuthenticationPrincipal Long user,@PathVariable long sid,@Valid @RequestBody Member body){
        Map<String,Object> store=access.require(user,sid,"members",true);Map<String,Object> target=users.byName(body.username);
        if(target==null)throw new ApiException(404,"用户尚未注册");
        long uid=Rows.number(target,"id");if(uid==Rows.number(store,"owner_id"))throw new ApiException(409,"不能修改所有者权限");
        stores.member(sid,uid,body.role,body.active==null||body.active);
        return Map.of("user_id",uid,"role",body.role,"active",body.active==null||body.active);
    }
}
