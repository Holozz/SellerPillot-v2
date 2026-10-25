FROM python:3.12-slim
WORKDIR /app
COPY backend/requirements.lock /app/backend/requirements.lock
RUN pip install --no-cache-dir -r backend/requirements.lock
COPY backend /app/backend
COPY alembic.ini /app/alembic.ini
ENV PYTHONPATH=/app/backend PYTHONUNBUFFERED=1
CMD ["uvicorn", "main:app", "--app-dir", "backend", "--host", "0.0.0.0"]
