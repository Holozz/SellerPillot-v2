package com.sellerpilot.finance;

import com.sellerpilot.common.*;
import com.sellerpilot.store.StoreAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v2/margin-reports")
public class MarginController {
    public record Query(@NotEmpty @Size(max=20)List<@NotNull @Positive Long> store_ids,@Positive long fx_rate_set_id,@NotNull LocalDate start,@NotNull LocalDate end,LocalDate fixed_rate_date){}
    public record Compare(@NotBlank String baseline_id,@NotBlank String target_id){}
    private final MarginJobs jobs;private final MarginCalculator calculator;private final MarginMapper reports;private final StoreAccess access;private final JsonMapper json;private final MarginComparison comparison;
    public MarginController(MarginJobs jobs,MarginCalculator calculator,MarginMapper reports,StoreAccess access,JsonMapper json,MarginComparison comparison){this.jobs=jobs;this.calculator=calculator;this.reports=reports;this.access=access;this.json=json;this.comparison=comparison;}
    @PostMapping("/compare") public Map<String,Object> compare(@AuthenticationPrincipal Long user,@Valid @RequestBody Compare body){
        Map<String,Object> a=require(user,body.baseline_id),b=require(user,body.target_id);
        return comparison.compare(json.readValue(String.valueOf(a.get("input_manifest")),Map.class),json.readValue(String.valueOf(b.get("input_manifest")),Map.class));
    }
    @GetMapping public List<Map<String,Object>> history(@AuthenticationPrincipal Long user,@RequestParam long store_id){
        access.require(user,store_id,"read",false);
        return reports.history(user,store_id).stream().map(row->{Map<String,Object> result=Rows.json(row);result.put("store_ids",json.readValue(String.valueOf(row.get("store_ids")),List.class));result.put("complete",Boolean.parseBoolean(String.valueOf(row.get("complete"))));return result;}).toList();
    }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String,Object> create(@AuthenticationPrincipal Long user, @Valid @RequestBody Query query){
        return jobs.submit(user,query);
    }
    @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Long user,@PathVariable String id){
        Map<String,Object> row=require(user,id);return Map.of("id",id,"input_manifest",json.readValue(String.valueOf(row.get("input_manifest")),Map.class),"result",json.readValue(String.valueOf(row.get("result")),Map.class));
    }
    @GetMapping("/{id}/summary")
    public Map<String,Object> summary(@AuthenticationPrincipal Long user,@PathVariable String id){
        authorize(user, reports.stores(id));
        var row = reports.summary(id);
        Map<String,Object> result = json.readValue(row.get("result").toString(), Map.class);
        result.put("products", row.get("products") == null ? List.of() : json.readValue(row.get("products").toString(), List.class));
        return Map.of("id",id,"input_manifest",json.readValue(row.get("input_manifest").toString(),Map.class),"result",result);
    }

    @GetMapping("/{id}/details")
    public Map<String,Object> details(@AuthenticationPrincipal Long user,@PathVariable String id,@RequestParam(defaultValue="0") int page){
        if (page < 0 || page >= 200) throw new ApiException(422,"明细页码须为 0 至 199");
        authorize(user, reports.stores(id));
        String rows = reports.details(id,page*50,page*50+49);
        return Map.of("page",page,"rows",rows == null ? List.of() : json.readValue(rows,List.class));
    }
    @PostMapping("/{id}/recompute") public Map<String,Object> recompute(@AuthenticationPrincipal Long user,@PathVariable String id){
        Map<String,Object> row=require(user,id);return calculator.compute(json.readValue(String.valueOf(row.get("input_manifest")),Map.class));
    }
    @PostMapping("/{id}/revalue-costs")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String,Object> revalueCosts(@AuthenticationPrincipal Long user, @PathVariable String id){
        return jobs.revalue(user,id);
    }
    private Map<String,Object> require(long user,String id){
        Map<String,Object> row=reports.get(id);
        authorize(user,row == null ? null : String.valueOf(row.get("store_ids")));
        return row;
    }

    private void authorize(long user,String storeIds){
        if(storeIds==null)throw new ApiException(404,"报表不存在");
        List<?> stores=json.readValue(storeIds,List.class);
        for(Object store:stores)access.require(user,((Number)store).longValue(),"read",false);
    }
}
