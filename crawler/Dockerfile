FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN apt-get update \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r requirements.txt

COPY gying_crawler.py .
RUN useradd --uid 10001 --create-home gying && chown -R gying:gying /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER gying

EXPOSE 8091

CMD ["python", "gying_crawler.py", "serve"]
