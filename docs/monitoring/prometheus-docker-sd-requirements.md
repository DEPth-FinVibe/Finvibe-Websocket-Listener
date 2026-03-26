# Prometheus Docker SD Requirements

This document defines the minimum requirements to scrape `Finvibe-Websocket-Listener` metrics with Prometheus using `docker_sd_configs`.

## Goal

- Avoid manual Prometheus target edits when Listener instances scale out/in.
- Collect per-instance metrics and aggregate metrics at the same time.

## Listener Requirements

1. Expose Prometheus endpoint via Actuator.
   - Endpoint: `/actuator/prometheus`
   - Actuator exposure includes `prometheus`.
2. Include Prometheus registry dependency.
   - `io.micrometer:micrometer-registry-prometheus`
3. Keep application port fixed to `8090` inside the container.

## Deployment Script Requirements

Listener container must be started with these labels:

- `prometheus.scrape=true`
- `prometheus.job=finvibe-websocket-listener`
- `prometheus.port=8090`
- `prometheus.path=/actuator/prometheus`

Current CD workflow adds these labels in `docker run`.

## Prometheus Requirements

1. Prometheus must have Docker API access.
   - Example: `/var/run/docker.sock` mounted as read-only.
2. Prometheus must be able to reach Listener container addresses.
   - Ensure shared docker network routing between Prometheus and Listener containers.
3. Add one-time `docker_sd_configs` scrape config.

Example `prometheus.yml` fragment:

```yaml
scrape_configs:
  - job_name: docker-autodiscovery
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 15s

    relabel_configs:
      - source_labels: [__meta_docker_container_label_prometheus_scrape]
        regex: "true"
        action: keep

      - source_labels: [__meta_docker_container_label_prometheus_job]
        target_label: job

      - source_labels: [__meta_docker_container_name]
        target_label: container

      - source_labels: [__meta_docker_container_label_prometheus_path]
        target_label: __metrics_path__

      - source_labels: [__address__, __meta_docker_container_label_prometheus_port]
        regex: "([^:]+)(?::\\d+)?;(\\d+)"
        replacement: "$1:$2"
        target_label: __address__
```

## Scale-out Behavior

- New Listener container instances are discovered automatically via Docker SD.
- Removed instances disappear automatically from targets.
- Per-instance view is available by default through Prometheus `instance` label.
- Aggregated view is available with `sum(...)` queries.

## Validation Checklist

1. Deploy Listener and confirm `/actuator/prometheus` responds.
2. Open Prometheus Targets page and verify Listener targets are `UP`.
3. Scale Listener instances up and confirm new targets appear without config edits.
4. Scale down and confirm removed targets disappear.
