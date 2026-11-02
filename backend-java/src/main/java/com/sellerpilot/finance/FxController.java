package com.sellerpilot.finance;

import com.sellerpilot.common.*;
import com.sellerpilot.store.StoreAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/stores/{sid}/fx-rate-sets")
public class FxController {
    public record Rate(@NotNull @Pattern(regexp="[A-Z]{3}")String base_currency,@NotNull LocalDate effective_date,
                       @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=12)BigDecimal rate){}
    public record RateSet(@NotBlank @Size(max=100)String source,@NotBlank @Size(max=64)String source_revision,
                          @NotEmpty @Size(max=10000)List<@Valid Rate> rates){}
    private final StoreAccess access;private final FxMapper fx;
    public FxController(StoreAccess access,FxMapper fx){this.access=access;this.fx=fx;}
    @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Long user,@PathVariable long sid){return fx.list(Rows.number(access.require(user,sid,"read",false),"owner_id")).stream().map(Rows::json).toList();}
    @PostMapping @Transactional public Map<String,Object> create(@AuthenticationPrincipal Long user,@PathVariable long sid,@Valid @RequestBody RateSet body){
        long owner=Rows.number(access.require(user,sid,"members",true),"owner_id");Set<String> keys=new HashSet<>();
        for(Rate rate:body.rates){if(rate.base_currency.equals("CNY")||!keys.add(rate.base_currency+":"+rate.effective_date))throw new ApiException(422,"人民币无需汇率，同一币种日期不能重复");}
        fx.insert(owner,user,body);long id=fx.lastId();fx.rates(id,body.rates);return Map.of("id",id,"rate_count",body.rates.size(),"quote_currency","CNY");
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable long id){
        long owner=Rows.number(access.require(user,sid,"read",false),"owner_id");Map<String,Object> set=fx.get(owner,id);if(set==null)throw new ApiException(404,"汇率集不存在");
        return Map.of("rate_set",Rows.json(set),"rates",fx.ratesFor(id).stream().map(Rows::json).toList());
    }
}
