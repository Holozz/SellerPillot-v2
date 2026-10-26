package com.sellerpilot.analytics;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MetricsCache {
    private static final Logger log=LoggerFactory.getLogger(MetricsCache.class);
    private final StringRedisTemplate redis;private final JsonMapper json;private final MeterRegistry metrics;private final long ttl;
    private volatile long bypassUntil;
    private final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> flights=new ConcurrentHashMap<>();
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    public MetricsCache(StringRedisTemplate redis,JsonMapper json,MeterRegistry metrics,@Value("${sellerpilot.cache.ttl-seconds}")long ttl){this.redis=redis;this.json=json;this.metrics=metrics;this.ttl=ttl;}
    public static String key(long sid,long version,QuerySpec.Normalized query){return "metrics:"+sid+":"+version+":v1:"+query.hash();}
    private void degraded(RuntimeException error){bypassUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);metrics.counter("sellerpilot.cache.degraded").increment();log.warn("event=cache_degraded exception={}",error.getClass().getSimpleName());}
    private Map<String,Object> read(String key){
        if(System.nanoTime()<bypassUntil)return null;
        try{
            String raw=redis.opsForValue().get(key);if(raw==null)return null;
            Map<String,Object> result=new LinkedHashMap<>(json.readValue(raw,Map.class));result.put("cache_hit",true);metrics.counter("sellerpilot.cache.hit").increment();return result;
        }catch(RuntimeException error){degraded(error);return null;}
    }
    public Map<String,Object> query(long sid,QuerySpec.Normalized query,long version,Supplier<Map<String,Object>> aggregate){
        String key=key(sid,version,query);Map<String,Object> hit=read(key);if(hit!=null)return hit;
        metrics.counter("sellerpilot.cache.miss").increment();
        if(flights.size()>=1024)throw new ApiException(503,"查询重建队列已满");
        CompletableFuture<Map<String,Object>> own=new CompletableFuture<>(),existing=flights.putIfAbsent(key,own);
        if(existing!=null){
            metrics.counter("sellerpilot.cache.coalesced").increment();
            try{return existing.get(1500,TimeUnit.MILLISECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"查询等待已中断");}
            catch(ExecutionException e){if(e.getCause() instanceof ApiException api)throw api;throw new ApiException(503,"查询重建失败");}
            catch(TimeoutException e){metrics.counter("sellerpilot.cache.wait.rejected").increment();throw new ApiException(503,"查询重建等待超时");}
        }
        String owner=UUID.randomUUID().toString();String lease="rebuild:"+key;boolean held=false;
        try{
            if(System.nanoTime()>=bypassUntil){
                try{
                    held=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lease,owner,Duration.ofSeconds(8)));
                    if(!held){
                        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1200);
                        while(System.nanoTime()<deadline&&System.nanoTime()>=bypassUntil){
                            hit=read(key);if(hit!=null){own.complete(hit);return hit;}
                            try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"查询等待已中断");}
                        }
                        if(System.nanoTime()>=bypassUntil){metrics.counter("sellerpilot.cache.wait.rejected").increment();throw new ApiException(503,"热点查询正在重建，请稍后重试");}
                    }else{
                        hit=read(key);if(hit!=null){own.complete(hit);return hit;}
                    }
                }catch(ApiException e){throw e;}catch(RuntimeException error){degraded(error);}
            }
            Map<String,Object> result=aggregate.get();
            // The aggregation transaction may have observed a newer version. Never relabel old data.
            String actualKey=key(sid,Rows.number(result,"data_version"),query);
            if(System.nanoTime()>=bypassUntil){
                try{redis.opsForValue().set(actualKey,json.writeValueAsString(result),Duration.ofSeconds(ttl));}
                catch(RuntimeException error){degraded(error);}
            }
            own.complete(result);return result;
        }catch(RuntimeException error){own.completeExceptionally(error);throw error;}
        finally{
            if(held){try{redis.execute(RELEASE,List.of(lease),owner);}catch(RuntimeException error){degraded(error);}}
            flights.remove(key,own);
        }
    }
}
