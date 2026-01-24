#!/usr/local/bin/bash
mvn clean install
docker build --platform linux/amd64 -t 10.21.3.30:80/archon-registry:latest .
docker push 10.21.3.30:80/archon-registry:latest
kubectl delete pod -l app=archon-registry