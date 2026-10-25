from pathlib import Path
import boto3
from botocore.exceptions import ClientError
from v2.settings import value, ROOT


class ObjectStore:
    def __init__(self):
        self.local = value('OBJECT_STORE', 's3') == 'local'
        self.root = Path(value('LOCAL_OBJECT_PATH', str(ROOT / '.data' / 'objects'))).resolve()
        self.bucket = value('S3_BUCKET', 'sellerpilot-v2')
        if not self.local:
            self.client = boto3.client('s3', endpoint_url=value('S3_ENDPOINT', 'http://localhost:9002'),
                aws_access_key_id=value('S3_ACCESS_KEY'), aws_secret_access_key=value('S3_SECRET_KEY'),
                region_name='us-east-1')

    def path(self, key):
        path = (self.root / key).resolve()
        if not path.is_relative_to(self.root):
            raise ValueError('Invalid object key')
        return path

    def put(self, key, data):
        if self.local:
            path = self.path(key); path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        else:
            try:
                self.client.head_bucket(Bucket=self.bucket)
            except ClientError as error:
                if error.response['Error']['Code'] not in ('404', 'NoSuchBucket', 'NotFound'):
                    raise
                try:
                    self.client.create_bucket(Bucket=self.bucket)
                except self.client.exceptions.BucketAlreadyOwnedByYou:
                    pass
            self.client.put_object(Bucket=self.bucket, Key=key, Body=data)

    def get(self, key):
        if self.local:
            return self.path(key).read_bytes()
        return self.client.get_object(Bucket=self.bucket, Key=key)['Body'].read()
