package com.sellerpilot.importing;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceImportMapper {
    @Select("SELECT * FROM source_import_batches WHERE store_id=#{sid} AND kind=#{kind} AND file_hash=#{hash} FOR UPDATE")
    java.util.Map<String,Object> batch(@Param("sid")long sid,@Param("kind")String kind,@Param("hash")String hash);
    @Insert("INSERT INTO source_import_batches(store_id,kind,file_hash,source_file_key,source_file,adapter_version,status,source_row_count,created_by) VALUES(#{sid},#{kind},#{hash},#{fileKey},#{file},'contract-v1','STAGING',#{count},#{user})")
    void insertBatch(@Param("sid")long sid,@Param("kind")String kind,@Param("hash")String hash,@Param("fileKey")String fileKey,
                     @Param("file")byte[] file,@Param("count")int count,@Param("user")long user);
    @Insert("<script>INSERT INTO source_import_rows(store_id,batch_id,source_row_number,source_record_key,source_record_hash,raw_fields,normalized_fields,validation_status) VALUES <foreach collection='rows' item='r' separator=','>(#{sid},#{batch},#{r.number},#{r.key},#{r.hash},CAST(#{r.raw} AS JSON),CAST(#{r.normalized} AS JSON),'VALID')</foreach></script>")
    void insertRows(@Param("sid")long sid,@Param("batch")long batch,@Param("rows")List<SourceImportService.StoredRow> rows);
    @Update("UPDATE source_import_batches SET status='PUBLISHED',published_at=UTC_TIMESTAMP(6) WHERE id=#{id} AND store_id=#{sid} AND status='STAGING'")
    void publish(@Param("sid")long sid,@Param("id")long id);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
}
