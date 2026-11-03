UPDATE margin_report_jobs
SET status='STALE', error_message='店铺数据已变化，请按最新数据重新生成'
WHERE status='FAILED' AND error_message='店铺数据已变化，请重新提交报表任务';
