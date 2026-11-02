package com.sellerpilot.importing;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.store.StoreAccess;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v2/stores/{sid}")
public class ImportHistoryController {
    private final SourceImportMapper imports;private final StoreAccess access;private final JsonMapper json;
    public ImportHistoryController(SourceImportMapper imports,StoreAccess access,JsonMapper json){this.imports=imports;this.access=access;this.json=json;}
    @GetMapping("/source-imports")
    public Map<String,Object> history(@AuthenticationPrincipal Long user,@PathVariable long sid,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){
        access.require(user,sid,"read",false);page(after,limit);return result(imports.history(sid,after,limit));
    }
    @GetMapping("/source-imports/{batch}/rows")
    public Map<String,Object> rows(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable long batch,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){
        access.require(user,sid,"read",false);page(after,limit);requireBatch(sid,batch);
        List<Map<String,Object>> rows=imports.pageRows(sid,batch,after,limit);
        for(Map<String,Object> row:rows)for(String field:List.of("raw_fields","normalized_fields","validation_errors")){
            Object value=row.get(field);if(value instanceof String text)row.put(field,json.readValue(text,Object.class));
        }
        return result(rows);
    }
    @GetMapping("/source-imports/{batch}/file")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable long batch){
        access.require(user,sid,"read",false);requireBatch(sid,batch);byte[] data=(byte[])imports.file(sid,batch).get("source_file");
        if(data==null)throw new ApiException(404,"该历史批次未保存原文件");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=import-"+batch+".csv").body(data);
    }
    @GetMapping("/source-revisions/{kind}")
    public Map<String,Object> revisions(@AuthenticationPrincipal Long user,@PathVariable long sid,@PathVariable String kind,@RequestParam String key,@RequestParam(defaultValue="0")long after,@RequestParam(defaultValue="50")int limit){
        access.require(user,sid,"read",false);page(after,limit);
        if(!Set.of("orders","order-items","refunds","procurement-batches").contains(kind)||key.isBlank()||key.length()>128)throw new ApiException(422,"修订类型或业务键无效");
        return result(imports.revisions(sid,kind,key,after,limit));
    }
    private void requireBatch(long sid,long batch){if(imports.detail(sid,batch)==null)throw new ApiException(404,"导入批次不存在");}
    private static void page(long after,int limit){if(after<0||limit<1||limit>100)throw new ApiException(422,"游标须非负，每页须为1至100条");}
    private static Map<String,Object> result(List<Map<String,Object>> rows){return Map.of("rows",rows.stream().map(Rows::json).toList(),"next_after",rows.isEmpty()?0:Rows.number(rows.getLast(),"id"));}
}
