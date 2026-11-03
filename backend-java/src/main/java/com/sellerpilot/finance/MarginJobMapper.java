package com.sellerpilot.finance;

import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
interface MarginJobMapper {
    @Select("SELECT id FROM margin_job_queue WHERE id=1 FOR UPDATE")
    int lockQueue();

    @Select("SELECT COUNT(*) FROM margin_report_jobs WHERE status IN ('QUEUED','RUNNING')")
    int pending();

    @Select("SELECT * FROM margin_report_jobs WHERE request_key=#{key}")
    Map<String,Object> byKey(String key);

    @Select("SELECT * FROM margin_report_jobs WHERE id=#{id}")
    Map<String,Object> get(String id);

    @Insert("""
        INSERT INTO margin_report_jobs(id,request_key,requested_by,query_json,store_versions,source_report_id,status)
        VALUES(#{id},#{key},#{user},#{query},#{versions},#{source},'QUEUED')
        """)
    void insert(@Param("id") String id, @Param("key") String key, @Param("user") long user,
                @Param("query") String query, @Param("versions") String versions, @Param("source") String source);

    @Select("SELECT * FROM margin_report_jobs WHERE status='QUEUED' ORDER BY queued_at,id LIMIT 1 FOR UPDATE")
    Map<String,Object> next();

    @Update("UPDATE margin_report_jobs SET status='RUNNING',attempts=attempts+1 WHERE id=#{id} AND status='QUEUED'")
    int claim(String id);

    @Update("UPDATE margin_report_jobs SET status=#{status},error_message=#{error} WHERE id=#{id} AND status='RUNNING'")
    int finish(@Param("id") String id, @Param("status") String status, @Param("error") String error);

    @Update("UPDATE margin_report_jobs SET status='INTERRUPTED',error_message='执行进程中断' WHERE status='RUNNING'")
    void interruptRunning();

    @Update("""
        UPDATE margin_report_jobs SET status='QUEUED',error_message=NULL,queued_at=CURRENT_TIMESTAMP(6)
        WHERE id=#{id} AND status IN ('FAILED','INTERRUPTED')
        """)
    int retry(String id);
}
