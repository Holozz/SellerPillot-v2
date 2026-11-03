package com.sellerpilot.finance;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.store.StoreAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class MarginJobs {
    private static final Logger log = LoggerFactory.getLogger(MarginJobs.class);
    private static final int CAPACITY = 32;
    private final MarginJobMapper jobs;
    private final MarginInputs inputs;
    private final MarginMapper reports;
    private final MarginCalculator calculator;
    private final StoreAccess access;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    MarginJobs(MarginJobMapper jobs, MarginInputs inputs, MarginMapper reports, MarginCalculator calculator,
               StoreAccess access, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.inputs = inputs;
        this.reports = reports;
        this.calculator = calculator;
        this.access = access;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    Map<String,Object> submit(long user, MarginController.Query raw) {
        long days = ChronoUnit.DAYS.between(raw.start(), raw.end());
        if (days < 1 || days > 366) throw new ApiException(422, "区间须为1至366天，结束日期不计入");
        var query = new MarginController.Query(raw.store_ids().stream().distinct().sorted().toList(),
            raw.fx_rate_set_id(), raw.start(), raw.end(), raw.fixed_rate_date());
        var versions = new LinkedHashMap<String,String>();
        long owner = 0;
        for (long id : query.store_ids()) {
            var store = access.require(user, id, "read", false);
            long currentOwner = ((Number) store.get("owner_id")).longValue();
            if (owner != 0 && owner != currentOwner) throw new ApiException(422, "报表店铺须属于同一所有者");
            owner = currentOwner;
            versions.put(Long.toString(id), store.get("data_version").toString());
        }
        String queryJson = json.writeValueAsString(query);
        String versionsJson = json.writeValueAsString(versions);
        String key = digest(user + ":" + queryJson + ":" + versionsJson);
        return tx.execute(status -> {
            jobs.lockQueue();
            var existing = jobs.byKey(key);
            if (existing != null) return view(existing);
            requireCapacity();
            String id = UUID.randomUUID().toString().replace("-", "");
            jobs.insert(id, key, user, queryJson, versionsJson);
            return view(jobs.get(id));
        });
    }

    Map<String,Object> get(long user, String id) {
        return view(require(user, id));
    }

    Map<String,Object> retry(long user, String id) {
        require(user, id);
        return tx.execute(status -> {
            jobs.lockQueue();
            var row = jobs.get(id);
            if (!Set.of("FAILED", "INTERRUPTED").contains(row.get("status"))) return view(row);
            requireCapacity();
            jobs.retry(id);
            return view(jobs.get(id));
        });
    }

    void recover() {
        jobs.interruptRunning();
    }

    void runNext() {
        var job = tx.execute(status -> {
            var next = jobs.next();
            if (next != null) jobs.claim((String) next.get("id"));
            return next;
        });
        if (job == null) return;
        String id = (String) job.get("id");
        long user = ((Number) job.get("requested_by")).longValue();
        var query = query(job);
        try {
            var manifest = inputs.capture(user, query);
            var expected = json.readValue(job.get("store_versions").toString(), Map.class);
            var actual = (Map<?,?>) manifest.get("store_versions");
            for (var entry : actual.entrySet()) {
                if (!entry.getValue().toString().equals(expected.get(entry.getKey())))
                    throw new ApiException(409, "店铺数据已变化，请重新提交报表任务");
            }
            var result = calculator.compute(manifest);
            String manifestJson = json.writeValueAsString(manifest);
            String resultJson = json.writeValueAsString(result);
            String storesJson = json.writeValueAsString(query.store_ids());
            tx.executeWithoutResult(status -> {
                for (long store : query.store_ids()) access.require(user, store, "read", true);
                if (jobs.finish(id, "SUCCEEDED", null) != 1) throw new IllegalStateException("任务状态已变化");
                reports.save(id, ((Number) manifest.get("owner_id")).longValue(), storesJson, manifestJson, resultJson, user);
            });
        } catch (ApiException failure) {
            jobs.finish(id, "FAILED", String.valueOf(failure.detail));
        } catch (RuntimeException failure) {
            log.error("Margin report job {} failed", id, failure);
            jobs.finish(id, "FAILED", "报表计算失败，可重试");
        }
    }

    private Map<String,Object> require(long user, String id) {
        var row = jobs.get(id);
        if (row == null || ((Number) row.get("requested_by")).longValue() != user)
            throw new ApiException(404, "报表任务不存在");
        for (long store : query(row).store_ids()) access.require(user, store, "read", false);
        return row;
    }

    private MarginController.Query query(Map<String,Object> row) {
        return json.readValue(row.get("query_json").toString(), MarginController.Query.class);
    }

    private void requireCapacity() {
        if (jobs.pending() >= CAPACITY) throw new ApiException(429, "报表任务队列已满，请稍后重试");
    }

    private static Map<String,Object> view(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>();
        result.put("id", row.get("id"));
        result.put("status", row.get("status"));
        result.put("attempts", row.get("attempts"));
        if ("SUCCEEDED".equals(row.get("status"))) result.put("report_id", row.get("id"));
        if (row.get("error_message") != null) result.put("error", row.get("error_message"));
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
