
import json
from minio import Minio

# Configuration matching the crawler
MINIO_ENDPOINT = "localhost:9000"
MINIO_ACCESS_KEY = "admin"
MINIO_SECRET_KEY = "miniopassword"
MINIO_BUCKET = "gying"

def set_public_policy():
    client = Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=False
    )
    
    policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": "*",
                "Action": ["s3:GetObject"],
                "Resource": [f"arn:aws:s3:::{MINIO_BUCKET}/*"]
            }
        ]
    }
    
    try:
        if not client.bucket_exists(MINIO_BUCKET):
            client.make_bucket(MINIO_BUCKET)
            print(f"Created bucket: {MINIO_BUCKET}")
            
        client.set_bucket_policy(MINIO_BUCKET, json.dumps(policy))
        print(f"✅ Successfully set public read policy for bucket '{MINIO_BUCKET}'")
    except Exception as e:
        print(f"❌ Error setting policy: {e}")

if __name__ == "__main__":
    set_public_policy()
