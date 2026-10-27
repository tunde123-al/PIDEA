FROM python:3.12-slim@sha256:a0f57064cbdcd6285fa45f51237ab0b35ea8ebaed06a1f5973e89e19dfb86704
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
