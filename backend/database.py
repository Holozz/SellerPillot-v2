import os

from dotenv import load_dotenv
from sqlalchemy import URL, create_engine
from sqlalchemy.orm import DeclarativeBase, Session

from pathlib import Path
load_dotenv(Path(__file__).resolve().parents[1] / '.env')

database_url = URL.create(
    drivername="mysql+pymysql",
    username=os.getenv("DB_USER"),
    password=os.getenv("DB_PASSWORD"),
    host=os.getenv("DB_HOST"),
    port=int(os.getenv("DB_PORT", "3307")),
    database=os.getenv("DB_NAME"),
    query={"charset": "utf8mb4"},
)

database_url = os.getenv('DATABASE_URL') or database_url
engine = create_engine(database_url, pool_pre_ping=True, pool_recycle=1800)


class Base(DeclarativeBase):
    pass

def get_db():
    with Session(engine) as session:
        yield session
