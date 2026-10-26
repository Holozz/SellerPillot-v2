package com.sellerpilot.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProductController {
    private final ProductService service;private final ProductMapper products;private final StoreAccess access;
    public ProductController(ProductService service,ProductMapper products,StoreAccess access){this.service=service;this.products=products;this.access=access;}
    public record Create(@JsonProperty("store_id") @Positive long storeId,@NotBlank @Pattern(regexp="^[A-Za-z0-9-]{3,30}$")String sku,
        @NotBlank @Size(max=200)String title,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2)BigDecimal price,
        @NotBlank @Pattern(regexp="^[A-Z]{3}$")String currency,@Min(0)int stock){}
    public record Edit(@NotBlank @Size(max=200)String title,@NotNull @DecimalMin("0") @Digits(integer=16,fraction=2)BigDecimal price,
        @NotBlank @Pattern(regexp="^[A-Z]{3}$")String currency,@Min(0)int stock,@Positive Long version){}
    @PostMapping("/products")@ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@AuthenticationPrincipal Long user,@Valid @RequestBody Create b){return service.create(user,b.storeId,b.sku,b.title,b.price,b.currency,b.stock);}
    @GetMapping("/products")public List<Map<String,Object>> list(@AuthenticationPrincipal Long user,@RequestParam("store_id")long sid,@RequestParam(defaultValue="100")int limit){
        access.require(user,sid,"read",false);return products.list(sid,0,Math.clamp(limit,1,100)).stream().map(Rows::json).toList();
    }
    @GetMapping("/api/v2/stores/{sid}/products")public Map<String,Object> page(@AuthenticationPrincipal Long user,@PathVariable long sid,
        @RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){
        access.require(user,sid,"read",false);int n=Math.clamp(limit,1,100);List<Map<String,Object>> rows=products.list(sid,after,n+1);
        Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("items",rows.stream().limit(n).map(Rows::json).toList());
        result.put("next_cursor",rows.size()>n?Rows.number(rows.get(n-1),"id"):null);return result;
    }
    private Map<String,Object> bySku(long user,long sid,String sku){access.require(user,sid,"read",false);Map<String,Object> p=products.bySku(sid,sku);if(p==null)throw new ApiException(404,"商品不存在");return p;}
    @GetMapping("/products/{sku}")public Map<String,Object> get(@AuthenticationPrincipal Long user,@PathVariable String sku,@RequestParam("store_id")long sid){return Rows.json(bySku(user,sid,sku));}
    @PutMapping("/products/{sku}")public Map<String,Object> edit(@AuthenticationPrincipal Long user,@PathVariable String sku,@RequestParam("store_id")long sid,@Valid @RequestBody Edit b){
        return service.update(user,sid,Rows.number(bySku(user,sid,sku),"id"),b.title,b.price,b.currency,b.stock,b.version);
    }
    @PutMapping("/api/v2/stores/{sid}/products/{id}")public Map<String,Object> editById(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable long id,@Valid @RequestBody Edit b){
        if(b.version==null)throw new ApiException(422,"需要商品版本");return service.update(user,sid,id,b.title,b.price,b.currency,b.stock,b.version);
    }
    @DeleteMapping("/products/{sku}")@ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long user,@PathVariable String sku,@RequestParam("store_id")long sid){service.delete(user,sid,Rows.number(bySku(user,sid,sku),"id"));}
    @GetMapping("/stores/{sid}/products/low-stock")public List<Map<String,Object>> low(@AuthenticationPrincipal Long user,@PathVariable long sid,
        @RequestParam(defaultValue="10")int threshold,@RequestParam(defaultValue="20")int limit){
        access.require(user,sid,"read",false);if(threshold<0||limit<1||limit>100)throw new ApiException(422,"阈值或数量无效");return products.low(sid,threshold,limit).stream().map(Rows::json).toList();
    }
}
