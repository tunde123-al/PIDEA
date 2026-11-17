FROM python:3.15-slim@sha256:65a65fc7b30b06cb6df4930fd4650697cb23f4c2698ab81fc87e4b031bc36928
RUN groupadd --gid 2000 pidea-files \
    && useradd --system --create-home --gid pidea-files pidea \
    && mkdir -p /uploads \
    && chown pidea:pidea-files /uploads \
    && chmod 2770 /uploads
WORKDIR /app
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir .
USER pidea
ENV PYTHONUNBUFFERED=1
CMD ["python", "-m", "pidea_data"]
