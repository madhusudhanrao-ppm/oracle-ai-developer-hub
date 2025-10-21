#!/bin/bash
echo "=== OCI Generative AI JET UI Project Debug Prompt ==="

echo "# 1. User/Env Info"
whoami
hostname
echo "Shell:" $SHELL
echo "Java version:"; java -version 2>&1 | head -n 2
echo "Node version:"; node -v
echo "NPM version:"; npm -v
echo "OS:"; uname -a
echo

echo "# 2. Terraform Status"
cd deploy/terraform || echo "Terraform folder not found"
terraform version
terraform show || echo "Terraform state not found"
cd ../..
echo

echo "# 3. Kubernetes Context and Resources"
kubectl config current-context
kubectl get nodes
kubectl get pods -A
kubectl get svc -A
kubectl get deploy -A
echo

echo "# 4. Namespace: Backend Pod Status/Log (Flyway/Spring Boot, Oracle, etc.)"
kubectl get pods -n backend
echo "--- Describe first backend pod:"
export BKPOD=$(kubectl get pods -n backend -o jsonpath='{.items[0].metadata.name}')
kubectl describe pod $BKPOD -n backend
echo "--- Init container log:"
kubectl logs $BKPOD -n backend -c unzip
echo "--- Backend container log (Spring Boot/Flyway/DB errors):"
kubectl logs $BKPOD -n backend -c backend
echo

echo "# 5. Deployment YAML diff (current/prod vs source)"
kubectl get deploy -n backend -o yaml | head -20
echo

echo "# 6. Environment Config Files"
ls -l genai.json || echo "Missing genai.json"
ls -l src/main/resources/application.properties || echo "No backend config found"
grep 'spring.datasource' src/main/resources/application.properties 2>/dev/null
grep 'flyway' src/main/resources/application.properties 2>/dev/null
echo

echo "# 7. Check Oracle DB Connectivity"
echo "Host in config:"
grep jdbc src/main/resources/application.properties 2>/dev/null
echo "OCI Autonomous DB wallet files:"
ls -l wallet* || echo "No wallet files in current directory"
echo

echo "# 8. Kustomize Overlay (prod)"
ls deploy/k8s/overlays/prod
echo

echo "# 9. Front End Health"
kubectl get pods -n web
kubectl logs $(kubectl get pods -n web -o jsonpath='{.items[0].metadata.name}') -n web
echo

echo "# 10. Load Balancer Public IP"
kubectl get svc -n backend -o jsonpath='{.items[?(@.spec.type=="LoadBalancer")].status.loadBalancer.ingress[0].ip}'
echo; echo
echo "=== End of Debug Report ==="