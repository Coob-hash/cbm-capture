# Same pinned base image as the CBM Python services.
FROM python:3.13-slim@sha256:9d2e5553305c7c7b0097999bb17187c69b921ccd6bc9d40e4bb5ebe652c00285 AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.lock /srv/requirements.lock
RUN pip install --no-cache-dir --root-user-action=ignore -r /srv/requirements.lock
RUN useradd --uid 10003 --create-home cbmapp
# The capture store. Created here, owned by the app user, so a new Docker volume mounted on it
# starts with the right owner.
RUN mkdir -p /data/captures && chown cbmapp:cbmapp /data/captures
COPY --chown=cbmapp:cbmapp cbm_api/ /srv/cbm_api/
USER cbmapp
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3)"]
# Client addresses are handled by the app (CBM_APP_TRUST_PROXY), not by uvicorn.
CMD ["python", "-m", "uvicorn", "cbm_api.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-proxy-headers", "--no-server-header"]

# Test image: the same runtime plus pytest and the suites. Not used in deployment.
FROM runtime AS test
USER root
COPY requirements-test.lock /srv/requirements-test.lock
RUN pip install --no-cache-dir --root-user-action=ignore -r /srv/requirements-test.lock
COPY --chown=cbmapp:cbmapp tests/ /srv/tests/
USER cbmapp
CMD ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider", "/srv/tests"]
