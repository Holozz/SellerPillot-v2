package com.sellerpilot.catalog;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import com.sellerpilot.store.StoreMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/stores/{sid}")
public class CatalogController {
    public record Product(@NotBlank @Pattern(regexp="^[A-Za-z0-9_-]{1,64}$")String internal_sku,
                          @NotBlank @Size(max=200)String title,
                          @NotBlank @Size(max=24)String base_unit){}
    public record Mapping(@NotBlank @Pattern(regexp="^[A-Za-z0-9_-]{1,128}$")String internal_sku){}
    private final CatalogMapper catalog;private final StoreAccess access;private final StoreMapper stores;
    public CatalogController(CatalogMapper catalog,StoreAccess access,StoreMapper stores){this.catalog=catalog;this.access=access;this.stores=stores;}
    @GetMapping("/catalog-products")
    public List<Map<String,Object>> products(@AuthenticationPrincipal Long user,@PathVariable long sid){
        Map<String,Object> store=access.require(user,sid,"read",false);return catalog.products(Rows.number(store,"owner_id"));
    }
    @PostMapping("/catalog-products")@ResponseStatus(HttpStatus.CREATED)@Transactional
    public Map<String,Object> create(@AuthenticationPrincipal Long user,@PathVariable long sid,@Valid @RequestBody Product body){
        Map<String,Object> store=access.require(user,sid,"members",true);long owner=Rows.number(store,"owner_id");
        catalog.insertProduct(owner,body.internal_sku,body.title,body.base_unit);stores.bump(sid);
        return catalog.product(owner,body.internal_sku);
    }
    @GetMapping("/product-mappings")
    public List<Map<String,Object>> mappings(@AuthenticationPrincipal Long user,@PathVariable long sid){
        access.require(user,sid,"read",false);return catalog.mappings(sid);
    }
    @PutMapping("/product-mappings/{sourceSku}")@Transactional
    public Map<String,Object> map(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable @Pattern(regexp="^[A-Za-z0-9_-]{1,128}$") String sourceSku,@Valid @RequestBody Mapping body){
        Map<String,Object> store=access.require(user,sid,"members",true);long owner=Rows.number(store,"owner_id");
        Map<String,Object> product=catalog.product(owner,body.internal_sku);
        if(product==null)throw new ApiException(404,"内部商品不存在");
        long version=catalog.nextVersion(sid,sourceSku);catalog.map(owner,sid,sourceSku,version,Rows.number(product,"id"),user);stores.bump(sid);
        return catalog.mappings(sid).stream().filter(row->Rows.text(row,"source_sku").equals(sourceSku)).findFirst().orElseThrow();
    }
}
