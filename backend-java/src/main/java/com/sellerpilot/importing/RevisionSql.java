package com.sellerpilot.importing;

import java.util.Map;
import com.sellerpilot.common.ApiException;

public final class RevisionSql {
    public static String history(Map<String,Object> params){
        String[] entity=switch(String.valueOf(params.get("kind"))){
            case "orders"->new String[]{"sales_order_revisions","external_order_id"};
            case "order-items"->new String[]{"sales_line_revisions","external_line_id"};
            case "refunds"->new String[]{"sales_refund_revisions","external_refund_id"};
            case "procurement-batches"->new String[]{"procurement_batch_revisions","source_batch_id"};
            default->throw new ApiException(422,"修订类型不支持");
        };
        return "SELECT f.*,r.batch_id,r.source_row_number,b.file_hash,b.adapter_version,b.created_by AS imported_by FROM "+entity[0]+" f JOIN source_import_rows r ON r.store_id=f.store_id AND r.id=f.source_row_id JOIN source_import_batches b ON b.store_id=r.store_id AND b.id=r.batch_id WHERE f.store_id=#{sid} AND f."+entity[1]+"=#{key} AND f.id>#{after} ORDER BY f.id LIMIT #{limit}";
    }
}
