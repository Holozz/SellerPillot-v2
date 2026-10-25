from pathlib import Path
import secrets
root=Path(__file__).resolve().parents[2]
target=root/'.env'
if target.exists():
    print('.env 已存在，未覆盖配置。')
else:
    lines=[]
    for line in (root/'.env.example').read_text(encoding='utf-8').splitlines():
        if '=' in line and line.split('=',1)[0] in ('DB_PASSWORD','MYSQL_ROOT_PASSWORD','JWT_SECRET_KEY','S3_SECRET_KEY'):
            line=line.split('=',1)[0]+'='+secrets.token_urlsafe(36)
        lines.append(line)
    target.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('已创建本地 .env 和随机凭据；请补充模型与 Embedding 配置。')
