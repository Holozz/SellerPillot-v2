package com.sellerpilot.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ActionController {
    private final ActionService service;private final ActionMapper actions;private final StoreAccess access;
    public ActionController(ActionService service,ActionMapper actions,StoreAccess access){this.service=service;this.actions=actions;this.access=access;}
    public record Create(@JsonProperty("product_id") @Positive long productId,@JsonProperty("product_version") @Positive long productVersion,@NotBlank @Size(max=200)String title){}
    public record Confirm(@NotNull @AssertTrue Boolean confirmed,@JsonProperty("payload_hash") @Pattern(regexp="[a-f0-9]{64}") @NotNull String hash,
                          @JsonProperty("idempotency_key") @NotBlank @Size(max=100)String key){}
    @GetMapping("/api/v2/stores/{sid}/proposals")public List<Map<String,Object>> list(@AuthenticationPrincipal Long user,@PathVariable long sid){access.require(user,sid,"read",false);return actions.list(sid).stream().map(service::view).toList();}
    @PostMapping("/api/v2/stores/{sid}/proposals")@ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@AuthenticationPrincipal Long user,@PathVariable long sid,@Valid @RequestBody Create b){return service.create(user,sid,b.productId,b.productVersion,b.title);}
    @PostMapping("/api/v2/stores/{sid}/proposals/{id}/confirm")
    public Map<String,Object> confirm(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String id,@Valid @RequestBody Confirm b){return service.confirm(user,sid,id,b.hash,b.key);}
    @PostMapping("/api/v2/stores/{sid}/proposals/{id}/reject")
    public Map<String,Object> reject(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String id){return service.reject(user,sid,id);}
    @GetMapping("/api/v2/stores/{sid}/audit")public List<Map<String,Object>> audit(@AuthenticationPrincipal Long user,@PathVariable long sid){access.require(user,sid,"read",false);return actions.audits(sid).stream().map(Rows::json).toList();}
    @RequestMapping({"/stores/{sid}/products/{pid}/title-drafts","/stores/{sid}/title-drafts/{id}","/stores/{sid}/title-drafts/{id}/confirm"})
    public void retired(){throw new ApiException(410,"旧标题草稿接口已退役，请使用操作审批接口");}
}
