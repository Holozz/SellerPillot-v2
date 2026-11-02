package com.sellerpilot.finance;

import com.sellerpilot.common.*;
import com.sellerpilot.store.StoreAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v2/margin-reports")
public class MarginController {
    public record Query(@NotEmpty @Size(max=20)List<@NotNull @Positive Long> store_ids,@Positive long fx_rate_set_id,@NotNull LocalDate start,@NotNull LocalDate end,LocalDate fixed_rate_date){}
    public record Compare(@NotBlank String baseline_id,@NotBlank String target_id){}
    private final MarginInputs inputs;private final MarginCalculator calculator;private final MarginMapper reports;private final StoreAccess access;private final JsonMapper json;private final MarginComparison comparison;
    public MarginController(MarginInputs inputs,MarginCalculator calculator,MarginMapper reports,StoreAccess access,JsonMapper json,MarginComparison comparison){this.inputs=inputs;this.calculator=calculator;this.reports=reports;this.access=access;this.json=json;this.comparison=comparison;}
    @PostMapping("/compare") public Map<String,Object> compare(@AuthenticationPrincipal Long user,@Valid @RequestBody Compare body){
        Map<String,Object> a=require(user,body.baseline_id),b=require(user,body.target_id);
        return comparison.compare(json.readValue(String.valueOf(a.get("input_manifest")),Map.class),json.readValue(String.valueOf(b.get("input_manifest")),Map.class));
    }
    @GetMapping public List<Map<String,Object>> history(@AuthenticationPrincipal Long user,@RequestParam long store_id){
        access.require(user,store_id,"read",false);
        return reports.history(user,store_id).stream().map(row->{Map<String,Object> result=Rows.json(row);result.put("store_ids",json.readValue(String.valueOf(row.get("store_ids")),List.class));result.put("complete",Boolean.parseBoolean(String.valueOf(row.get("complete"))));return result;}).toList();
    }
    @PostMapping public Map<String,Object> create(@AuthenticationPrincipal Long user,@Valid @RequestBody Query query){
        long days=ChronoUnit.DAYS.between(query.start,query.end);if(days<1||days>366)throw new ApiException(422,"区间须为1至366天，结束日期不计入");
        Map<String,Object> manifest=inputs.capture(user,query),result=calculator.compute(manifest);String id=UUID.randomUUID().toString().replace("-","");
        // Calculation runs after the snapshot transaction has closed.
        for(long store:query.store_ids)access.require(user,store,"read",false);
        reports.save(id,((Number)manifest.get("owner_id")).longValue(),json.writeValueAsString(manifest.get("store_ids")),json.writeValueAsString(manifest),json.writeValueAsString(result),user);
        return Map.of("id",id,"result",result);
    }
    @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Long user,@PathVariable String id){
        Map<String,Object> row=require(user,id);return Map.of("id",id,"input_manifest",json.readValue(String.valueOf(row.get("input_manifest")),Map.class),"result",json.readValue(String.valueOf(row.get("result")),Map.class));
    }
    @PostMapping("/{id}/recompute") public Map<String,Object> recompute(@AuthenticationPrincipal Long user,@PathVariable String id){
        Map<String,Object> row=require(user,id);return calculator.compute(json.readValue(String.valueOf(row.get("input_manifest")),Map.class));
    }
    private Map<String,Object> require(long user,String id){
        Map<String,Object> row=reports.get(id);if(row==null)throw new ApiException(404,"报表不存在");
        List<?> stores=json.readValue(String.valueOf(row.get("store_ids")),List.class);for(Object store:stores)access.require(user,((Number)store).longValue(),"read",false);return row;
    }
}
