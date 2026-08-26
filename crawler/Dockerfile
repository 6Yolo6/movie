FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY gying_crawler.py .

EXPOSE 8091

CMD ["python", "gying_crawler.py", "serve"]
