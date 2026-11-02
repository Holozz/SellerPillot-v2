package com.sellerpilot.finance;

import com.sellerpilot.common.*;
import com.sellerpilot.store.StoreAccess;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MarginInputs {
    private final StoreAccess access;private final FxMapper fx;private final MarginMapper reports;
    public MarginInputs(StoreAccess access,FxMapper fx,MarginMapper reports){this.access=access;this.fx=fx;this.reports=reports;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> capture(long user,MarginController.Query query){
        List<Long> ids=query.store_ids().stream().distinct().sorted().toList();long owner=0;
        Map<String,Object> versions=new LinkedHashMap<>();List<Map<String,Object>> sales=new ArrayList<>(),refunds=new ArrayList<>();
        for(long id:ids){
            Map<String,Object> store=access.require(user,id,"read",false);long current=Rows.number(store,"owner_id");
            if(owner!=0&&owner!=current)throw new ApiException(422,"报表店铺须属于同一所有者");owner=current;versions.put(String.valueOf(id),store.get("data_version"));
            sales.addAll(reports.sales(id,query.start().atStartOfDay(),query.end().atStartOfDay()));refunds.addAll(reports.refunds(id,query.start().atStartOfDay(),query.end().atStartOfDay()));
            if(sales.size()+refunds.size()>10000)throw new ApiException(422,"当前同步报表最多10000条销售/退款记录，请缩小范围");
        }
        Map<String,Object> set=fx.get(owner,query.fx_rate_set_id());if(set==null)throw new ApiException(404,"汇率集不存在或不属于该卖家");
        Map<String,Object> input=new LinkedHashMap<>();input.put("owner_id",owner);input.put("store_ids",ids);input.put("store_versions",versions);input.put("start",query.start().toString());input.put("end",query.end().toString());input.put("timezone","UTC");input.put("currency","CNY");input.put("rule_version","sales-margin-v1");input.put("rounding","SUM_THEN_HALF_UP_2");input.put("fx_policy",query.fixed_rate_date()==null?"EXACT_BUSINESS_DATE":"FIXED_DATE");if(query.fixed_rate_date()!=null)input.put("fixed_rate_date",query.fixed_rate_date().toString());input.put("fx_rate_set",Rows.json(set));input.put("rates",fx.ratesFor(query.fx_rate_set_id()).stream().map(Rows::json).toList());input.put("sales",sales.stream().map(Rows::json).toList());input.put("refunds",refunds.stream().map(Rows::json).toList());return input;
    }
}
