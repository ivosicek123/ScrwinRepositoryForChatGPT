FROM python:3.12-slim

WORKDIR /app
COPY requirements.lock.txt .
RUN pip install --no-cache-dir --require-hashes -r requirements.lock.txt

COPY app ./app

# Least privilege: run as non-root
RUN useradd --system --uid 10001 --no-create-home elfall && chown -R elfall /app
USER 10001

ENV NETWORK=testnet
ENV PYTHONUNBUFFERED=1

# RunPod serverless entry
CMD ["python", "-u", "-m", "app.handler"]
