package com.sellerpilot.store;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;

@Service
public class StoreAccess {
    private final StoreMapper mapper;
    public StoreAccess(StoreMapper mapper){this.mapper=mapper;}
    public Map<String,Object> require(long user,long store,String capability,boolean lock){
        Map<String,Object> row=lock?mapper.lock(store):mapper.get(store);
        if(row==null)throw new ApiException(404,"店铺不存在");
        if(Rows.number(row,"owner_id")==user)return row;
        String role=lock?mapper.lockedRole(store,user):mapper.role(store,user);
        if(role==null)throw new ApiException(404,"店铺不存在");
        if(capability.equals("members")||(!capability.equals("read")&&!role.equals("operator")))
            throw new ApiException(403,"当前角色无此操作权限");
        return row;
    }
}
