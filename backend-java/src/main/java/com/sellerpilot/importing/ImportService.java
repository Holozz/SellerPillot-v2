package com.sellerpilot.importing;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import com.sellerpilot.store.StoreMapper;

@Service
public class ImportService {
    private final ImportMapper mapper;private final StoreAccess access;private final StoreMapper stores;
    public ImportService(ImportMapper mapper,StoreAccess access,StoreMapper stores){this.mapper=mapper;this.access=access;this.stores=stores;}
    @Transactional public Map<String,Object> apply(long user,long sid,CsvParser.Parsed parsed){
        Map<String,Object> store=access.require(user,sid,"members",true); // Existing import contract is owner-only.
        Map<String,Object> prior=mapper.batch(sid,parsed.kind(),parsed.hash());
        if(prior!=null)return Map.of("import_job_id",prior.get("id"),"row_count",prior.get("row_count"),"data_version",prior.get("data_version"),"duplicate",true);
        List<String> ids=parsed.rows().stream().map(CsvParser.Row::external).distinct().toList();
        Map<String,Map<String,Object>> orders=mapper.orders(sid,ids).stream().collect(Collectors.toMap(r->Rows.text(r,"external_order_id"),Function.identity()));
        List<Map<String,Object>> errors=new ArrayList<>();int total=0;
        if(parsed.kind().equals("orders")){
            for(CsvParser.Row row:parsed.rows())if(orders.containsKey(row.external())&&!row.currency().equals(orders.get(row.external()).get("currency"))){
                total++;if(errors.size()<50)errors.add(Map.of("row",row.row(),"field","currency","reason","已有订单币种不可修改"));
            }
            if(total>0)throw CsvParser.error(errors,total);
            for(int i=0;i<parsed.rows().size();i+=500)mapper.saveOrders(sid,parsed.rows().subList(i,Math.min(i+500,parsed.rows().size())));
        }else{
            Map<String,Map<String,Object>> products=mapper.products(sid,parsed.rows().stream().map(CsvParser.Row::sku).distinct().toList()).stream().collect(Collectors.toMap(r->Rows.text(r,"sku"),Function.identity()));
            Map<String,Map<String,Object>> old=mapper.items(sid,ids).stream().collect(Collectors.toMap(r->Rows.text(r,"external_order_id")+"#"+Rows.number(r,"line_no"),Function.identity()));
            List<ImportMapper.Item> items=new ArrayList<>();
            for(CsvParser.Row row:parsed.rows()){
                Map<String,Object> order=orders.get(row.external()),product=products.get(row.sku()),existing=old.get(row.external()+"#"+row.line());
                String reason=order==null||product==null?"订单或商品在本店铺不存在":
                    !order.get("currency").equals(product.get("currency"))?"商品与订单币种不一致":
                    existing!=null&&Rows.number(existing,"product_id")!=Rows.number(product,"id")?"已有明细不可替换商品":null;
                if(reason!=null){total++;if(errors.size()<50)errors.add(Map.of("row",row.row(),"field","record","reason",reason));}
                else items.add(new ImportMapper.Item(sid,Rows.number(order,"id"),Rows.number(product,"id"),row.line(),row.quantity(),row.price(),row.refund()));
            }
            if(total>0)throw CsvParser.error(errors,total);
            for(int i=0;i<items.size();i+=500)mapper.saveItems(items.subList(i,Math.min(i+500,items.size())));
        }
        stores.bump(sid);long version=Rows.number(store,"data_version")+1;
        mapper.record(sid,parsed.kind(),parsed.hash(),parsed.rows().size(),version,user);
        return Map.of("import_job_id",mapper.lastId(),"row_count",parsed.rows().size(),"data_version",version,"duplicate",false);
    }
}
