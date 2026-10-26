package com.sellerpilot.product;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import com.sellerpilot.store.StoreMapper;

@Service
public class ProductService {
    private final ProductMapper products;private final StoreAccess access;private final StoreMapper stores;
    public ProductService(ProductMapper products,StoreAccess access,StoreMapper stores){this.products=products;this.access=access;this.stores=stores;}
    @Transactional public Map<String,Object> create(long user,long sid,String sku,String title,BigDecimal price,String currency,int stock){
        access.require(user,sid,"write",true);products.insert(sid,sku,title,price,currency,stock);long id=products.lastId();stores.bump(sid);return Rows.json(products.get(sid,id));
    }
    @Transactional public Map<String,Object> update(long user,long sid,long id,String title,BigDecimal price,String currency,int stock,Long version){
        access.require(user,sid,"write",true);Map<String,Object> p=products.lock(sid,id);
        if(p==null)throw new ApiException(404,"商品不存在");
        if(version!=null&&version!=Rows.number(p,"version"))throw new ApiException(409,"商品版本已变化");
        products.update(sid,id,title,price,currency,stock);stores.bump(sid);return Rows.json(products.get(sid,id));
    }
    @Transactional public void delete(long user,long sid,long id){
        access.require(user,sid,"write",true);if(products.lock(sid,id)==null)throw new ApiException(404,"商品不存在");
        products.delete(sid,id);stores.bump(sid);
    }
}
