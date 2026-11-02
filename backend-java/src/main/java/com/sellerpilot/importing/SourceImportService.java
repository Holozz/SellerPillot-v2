package com.sellerpilot.importing;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import com.sellerpilot.store.StoreMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SourceImportService {
    public record StoredRow(int number,String key,String hash,String raw,String normalized){}
    private final SourceImportMapper mapper;private final StoreAccess access;private final StoreMapper stores;private final JsonMapper json;
    public SourceImportService(SourceImportMapper mapper,StoreAccess access,StoreMapper stores,JsonMapper json){this.mapper=mapper;this.access=access;this.stores=stores;this.json=json;}
    @Transactional public Map<String,Object> apply(long user,long sid,PlatformCsvParser.Parsed parsed){
        Map<String,Object> store=access.require(user,sid,"members",true);
        if(!parsed.platform().equals(store.get("platform"))||!parsed.storeKey().equals(store.get("source_store_key")))throw new ApiException(422,"文件平台或来源店铺标识不匹配");
        Map<String,Object> prior=mapper.batch(sid,parsed.kind(),parsed.hash());
        if(prior!=null)return Map.of("import_id",prior.get("id"),"row_count",prior.get("source_row_count"),"duplicate",true,"status",prior.get("status"));
        String fileKey="sha256/"+parsed.hash();
        mapper.insertBatch(sid,parsed.kind(),parsed.hash(),fileKey,parsed.file(),parsed.rows().size(),user);
        long batch=mapper.lastId();
        List<StoredRow> rows=new ArrayList<>(parsed.rows().size());
        try{for(PlatformCsvParser.Row row:parsed.rows())rows.add(new StoredRow(row.number(),row.key(),row.hash(),json.writeValueAsString(row.raw()),json.writeValueAsString(row.normalized())));}
        catch(Exception e){throw new IllegalStateException("规范化来源记录序列化失败",e);}
        for(int i=0;i<rows.size();i+=500)mapper.insertRows(sid,batch,rows.subList(i,Math.min(i+500,rows.size())));
        mapper.publish(sid,batch);stores.bump(sid);
        return Map.of("import_id",batch,"row_count",rows.size(),"duplicate",false,"status","PUBLISHED","data_version",Rows.number(store,"data_version")+1);
    }
}
