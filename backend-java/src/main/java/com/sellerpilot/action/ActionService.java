package com.sellerpilot.action;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.product.ProductMapper;
import com.sellerpilot.store.StoreAccess;
import com.sellerpilot.store.StoreMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ActionService {
    private static final Logger log=LoggerFactory.getLogger(ActionService.class);
    private final ActionMapper actions;private final ProductMapper products;private final StoreAccess access;private final StoreMapper stores;private final JsonMapper json;
    public ActionService(ActionMapper actions,ProductMapper products,StoreAccess access,StoreMapper stores,JsonMapper json){this.actions=actions;this.products=products;this.access=access;this.stores=stores;this.json=json;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static LocalDateTime now(){return LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);}
    private static LocalDateTime time(Object value){return value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    public Map<String,Object> view(Map<String,Object> proposal){
        Map<String,Object> result=Rows.json(proposal);
        if("pending".equals(proposal.get("status"))&&!time(proposal.get("expires_at")).isAfter(now()))result.put("status","expired");return result;
    }
    @Transactional public Map<String,Object> create(long user,long sid,long pid,long version,String title){
        access.require(user,sid,"write",true);Map<String,Object> product=products.lock(sid,pid);
        if(product==null)throw new ApiException(404,"商品不存在");
        if(Rows.number(product,"version")!=version)throw new ApiException(409,"商品版本已变化");
        title=title.trim();if(title.isEmpty()||title.length()>200||title.equals(product.get("title")))throw new ApiException(422,"新标题须有变化且不超过200字符");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((pid+"|"+version+"|"+title).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        String id=id();actions.create(id,sid,pid,user,version,Rows.text(product,"title"),title,hash,now().plusHours(24));return view(actions.get(sid,id));
    }
    @Transactional public Map<String,Object> confirm(long user,long sid,String id,String hash,String key){
        try{
            access.require(user,sid,"confirm",true);Map<String,Object> proposal=actions.lock(sid,id);
            if(proposal==null)throw new ApiException(404,"草稿不存在");
            if(!hash.equals(proposal.get("payload_hash")))throw new ApiException(409,"确认内容与草稿不一致");
            String prior=actions.proposalForKey(sid,key);if(prior!=null&&!prior.equals(id))throw new ApiException(409,"幂等键已用于其他草稿");
            if("applied".equals(proposal.get("status")))return json.readValue(actions.receipt(id),Map.class);
            if(!"pending".equals(proposal.get("status"))||!time(proposal.get("expires_at")).isAfter(now()))throw new ApiException(409,"草稿已处理或过期");
            long pid=Rows.number(proposal,"product_id");Map<String,Object> product=products.lock(sid,pid);
            if(product==null||Rows.number(product,"version")!=Rows.number(proposal,"product_version"))throw new ApiException(409,"商品版本冲突，请重新生成草稿");
            products.title(sid,pid,Rows.text(proposal,"proposed_title"));stores.bump(sid);actions.status(id,"applied");
            Map<String,Object> receipt=Map.of("proposal_id",id,"product_id",pid,"title",proposal.get("proposed_title"),
                "product_version",Rows.number(product,"version")+1,"applied_by",user,"applied_at",Instant.now().toString());
            String encoded=json.writeValueAsString(receipt);
            actions.execution(id(),sid,id,key,encoded);actions.audit(sid,user,"update_product_title",Long.toString(pid),encoded);
            return receipt;
        }catch(ApiException e){log.info("event=approval_rejected user={} store={} proposal={} status={} reason={}",user,sid,id,e.status,e.getMessage());throw e;}
    }
    @Transactional public Map<String,Object> reject(long user,long sid,String id){
        access.require(user,sid,"confirm",true);Map<String,Object> proposal=actions.lock(sid,id);
        if(proposal==null)throw new ApiException(404,"草稿不存在");
        if(!"pending".equals(proposal.get("status")))throw new ApiException(409,"草稿已处理");
        actions.status(id,"rejected");actions.audit(sid,user,"reject_proposal",id,"{}");return view(actions.get(sid,id));
    }
}
